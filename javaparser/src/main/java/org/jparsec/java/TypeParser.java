package org.jparsec.java;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import org.codehaus.jparsec.Parser;
import org.codehaus.jparsec.Parsers;
import org.codehaus.jparsec.Scanners;
import org.codehaus.jparsec.Terminals;
import org.codehaus.jparsec.functors.Map;
import org.codehaus.jparsec.functors.Map2;
import org.codehaus.jparsec.pattern.CharPredicate;
import org.codehaus.jparsec.pattern.Patterns;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

/**
 * A parser for any valid reified Java type expression.
 * That is, type variables aren't allowed in the parsed string.
 *
 * <p>Currently, no type bounds checking is performed, which means you could potentially create
 * insane types such as {@code Enum<String>}.
 */
// TODO: Perform type bounds checking once we find a good type inference library to use.
public final class TypeParser {

  private static final CharPredicate JAVA_IDENTIFIER_START = new CharPredicate() {
    @Override public boolean isChar(char c) {
      return Character.isJavaIdentifierStart(c);
    }
  };

  private static final CharPredicate JAVA_IDENTIFIER_PART = new CharPredicate() {
    @Override public boolean isChar(char c) {
      return c == ';' || Character.isJavaIdentifierPart(c);
    }
  };

  private static final Parser<?> SINGLE_IDENTIFIER = Scanners.pattern(
      Patterns.isChar(JAVA_IDENTIFIER_START).next(Patterns.isChar(JAVA_IDENTIFIER_PART).many()),
      "identifier");

  private static final Terminals TERMS = Terminals.caseSensitive(
      SINGLE_IDENTIFIER.source(),
      new String[] {"<", ">", "&", ",", "[", "]", "?", "@", "."},
      new String[] {"extends", "super"});

  private static final Parser<String> FQN = Terminals.Identifier.PARSER
      .sepBy1(TERMS.token("."))
      .map(new Map<List<String>, String>() {
        @Override public String map(List<String> parts) {
          return Joiner.on('.').join(parts);
        }
      });

  private static final ImmutableMap<String, Class<?>> PRIMITIVE_TYPES = mapByName(
      void.class, boolean.class, byte.class, short.class, int.class, long.class,
      float.class, double.class);

  private static ImmutableMap<String, Class<?>> PRIMITIVE_ARRAY_CLASSES = mapByName(
      boolean[].class, byte[].class, short[].class, int[].class,
      long[].class, float[].class, double[].class);

  private final ClassLoader classloader;
  private final Parser<Class<?>> rawTypeParser = FQN.map(new Map<String, Class<?>>() {
        @Override public Class<?> map(String name) {
          Class<?> primitiveType = PRIMITIVE_TYPES.get(name);
          if (primitiveType != null) return primitiveType;
          if (name.indexOf('.') < 0) {
            name = "java.lang." + name;
          }
          return loadClass(name);
        }
      });

  public TypeParser() {
    this(TypeParser.class.getClassLoader());
  }

  /** Create a type parser with {@code classloader} used to load classes. */
  public TypeParser(ClassLoader classloader) {
    this.classloader = checkNotNull(classloader);
  }

  /** Parses {@code string} to a {@link TypeToken}. */
  public TypeToken<?> parse(String string) {
    Parser.Reference<Type> ref = Parser.newReference();
    ref.set(couldBeCanonicalArray(
        Parsers.or(parameterizedType(ref.lazy()), arrayClass(), rawTypeParser)));
    return TypeToken.of(
        ref.get().from(TERMS.tokenizer(), Scanners.WHITESPACES.optional()).parse(string));
  }

  private Parser<ParameterizedType> parameterizedType(Parser<Type> typeArg) {
    return Parsers.sequence(
        rawTypeParser,
        typeParameter(typeArg)
            .sepBy(TERMS.token(","))
            .between(TERMS.token("<"), TERMS.token(">")),
        new Map2<Class<?>, List<Type>, ParameterizedType>() {
          @Override public ParameterizedType map(Class<?> raw, List<Type> params) {
            return Types.newParameterizedType(raw, params);
          }
        });
  }

  private Parser<Class<?>> arrayClass() {
    Parser<Class<?>> componentType = FQN.next(
        new Map<String, Parser<? extends Class<?>>>() {
          @Override public Parser<? extends Class<?>> map(String name) {
            Class<?> primitiveArray = PRIMITIVE_ARRAY_CLASSES.get("[" + name);
            if (primitiveArray != null) return Parsers.constant(primitiveArray);
            if (name.startsWith("L") && name.endsWith(";")) {
              String className = name.substring(1, name.length() - 1);
              return Parsers.constant(Types.newArrayType(loadClass(className)));
            } else {
              return Parsers.expect("array class internal name");
            }
          }
        });
    return TERMS.token("[")
        .next(componentType.prefix(TERMS.token("[").retn(new Map<Class<?>, Class<?>>() {
          @Override public Class<?> map(Class<?> type) {
            return Types.newArrayType(type);
          }
        })));
  }

  private Class<?> loadClass(String name) {
    try {
      return Class.forName(name, false, classloader);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private static Parser<Type> couldBeCanonicalArray(Parser<Type> typeParser) {
    return typeParser.postfix(TERMS.phrase("[", "]").retn(new Map<Type, Type>() {
      @Override public Type map(Type componentType) {
        return Types.newArrayType(componentType);
      }
    }));
  }

  private static Parser<Type> typeParameter(Parser<Type> typeParser) {
    return Parsers.or(
        TERMS.phrase("?", "extends").next(typeParser).map(new Map<Type, Type>() {
          @Override public Type map(Type bound) {
            return Types.subtypeOf(bound);
          }
        }),
        TERMS.phrase("?", "super").next(typeParser).map(new Map<Type, Type>() {
          @Override public Type map(Type bound) {
            return Types.supertypeOf(bound);
          }
        }),
        TERMS.token("?").retn(Types.subtypeOf(Object.class)),
        typeParser);
  }

  private static ImmutableMap<String, Class<?>> mapByName(Class<?>... classes) {
    ImmutableMap.Builder<String, Class<?>> builder = ImmutableMap.builder();
    for (Class<?> cls : classes) {
      builder.put(cls.getName(), cls);
    }
    return builder.build();
  }
}
