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

import com.google.common.collect.ImmutableList;
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
      return Character.isJavaIdentifierPart(c);
    }
  };

  private static final Parser<?> SINGLE_IDENTIFIER = Scanners.pattern(
      Patterns.isChar(JAVA_IDENTIFIER_START).next(Patterns.isChar(JAVA_IDENTIFIER_PART).many()),
      "identifier");

  private static final Terminals TERMS = Terminals.caseSensitive(
      SINGLE_IDENTIFIER.sepBy(Scanners.isChar('.'))
          .followedBy(Scanners.isChar(';').optional())
          .source(),
      new String[] {"<", ">", "&", ",", "[", "]", "?", "@"},
      new String[] {
          "extends", "super",
          "void", "boolean", "byte", "short", "int", "long", "float", "double"});

  private static final Parser<Class<?>> PRIMITIVE_TYPE = Parsers.or(
      TERMS.token("void").retn(void.class),
      TERMS.token("boolean").retn(boolean.class),
      TERMS.token("byte").retn(byte.class),
      TERMS.token("short").retn(short.class),
      TERMS.token("int").retn(int.class),
      TERMS.token("long").retn(long.class),
      TERMS.token("float").retn(float.class),
      TERMS.token("double").retn(double.class));

  private static ImmutableMap<String, Class<?>> INTERNAL_PRIMITIVE_ARRAY_CLASSES =
      getInternalPrimitiveArrayClasses();

  private final ClassLoader classloader;
  private final Parser<Class<?>> rawTypeParser = Terminals.Identifier.PARSER.map(
      new Map<String, Class<?>>() {
        @Override public Class<?> map(String name) {
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
    Parser<Class<?>> classParser = Parsers.or(PRIMITIVE_TYPE, internalArrayClass(), rawTypeParser);
    ref.set(couldBeCanonicalArray(Parsers.or(parameterizedType(ref.lazy()), classParser)));
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

  private Parser<Class<?>> internalArrayClass() {
    Parser<Class<?>> componentType = Terminals.Identifier.PARSER.next(
        new Map<String, Parser<? extends Class<?>>>() {
          @Override public Parser<? extends Class<?>> map(String name) {
            Class<?> primitiveArray = INTERNAL_PRIMITIVE_ARRAY_CLASSES.get("[" + name);
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

  private static ImmutableMap<String, Class<?>> getInternalPrimitiveArrayClasses() {
    ImmutableMap.Builder<String, Class<?>> builder = ImmutableMap.builder();
    for (Class<?> arrayClass : ImmutableList.of(
        boolean[].class,
        byte[].class,
        short[].class,
        int[].class,
        long[].class,
        float[].class,
        double[].class)) {
      builder.put(arrayClass.getName(), arrayClass);
    }
    return builder.build();
  }
}
