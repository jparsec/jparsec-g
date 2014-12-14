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
import org.codehaus.jparsec.functors.Unary;
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
    @Override public boolean isChar(char c) { return Character.isJavaIdentifierStart(c); }
  };

  private static final CharPredicate JAVA_IDENTIFIER_PART = new CharPredicate() {
    @Override public boolean isChar(char c) {
      // ";" is for internal array class names such as [Ljava.lang.String;
      // Since ';' is not used in any other grammar, we just treat it as part of identifier.
      // Class.forName() can reject incorrectly placed ';' anyway.
      return c == ';' || Character.isJavaIdentifierPart(c);
    }
  };

  private static final Parser<?> WORD = Patterns.isChar(JAVA_IDENTIFIER_START)
      .next(Patterns.isChar(JAVA_IDENTIFIER_PART).many())
      .toScanner("identifier");

  private static final Terminals TERMS = Terminals
      .operators("<", ">", "&", ",", "[", "]", "?", "@", ".")
      .words(WORD.source())
      .keywords("extends", "super")
      .build();

  private static final Parser<String> FQN = Terminals.Identifier.PARSER
      .sepBy1(TERMS.token("."))
      .map(new Map<List<String>, String>() {
        final Joiner joiner = Joiner.on('.');
        @Override public String map(List<String> parts) { return joiner.join(parts); }
      });

  private static final ImmutableMap<String, Class<?>> PRIMITIVE_TYPES = mapByName(
      void.class, boolean.class, byte.class, short.class, int.class, long.class,
      float.class, double.class);

  private static ImmutableMap<String, Class<?>> PRIMITIVE_ARRAY_TYPES = mapByName(
      boolean[].class, byte[].class, short[].class, int[].class,
      long[].class, float[].class, double[].class);

  private final ClassLoader classloader;

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
    Parser<Type> type = Parsers.or(
        wildcardType(ref.lazy()), parameterizedType(ref.lazy()), arrayClass(), rawType());
    ref.set(type.postfix(TERMS.phrase("[", "]").retn(new Unary<Type>() {
      @Override public Type map(Type componentType) { return Types.newArrayType(componentType); }
    })));
    return TypeToken.of(
        ref.get().from(TERMS.tokenizer(), Scanners.WHITESPACES.optional()).parse(string));
  }

  private Parser<Class<?>> rawType() {
    return FQN.map(new Map<String, Class<?>>() {
      @Override public Class<?> map(String name) {
        Class<?> primitiveType = PRIMITIVE_TYPES.get(name);
        if (primitiveType != null) return primitiveType;
        return loadClass(name.indexOf('.') < 0 ? "java.lang." + name : name);
      }
    });
  }

  private Parser<ParameterizedType> parameterizedType(Parser<Type> typeArg) {
    return Parsers.sequence(
        rawType(),
        Parsers.between(TERMS.token("<"), typeArg.sepBy(TERMS.token(",")), TERMS.token(">")),
        new Map2<Class<?>, List<Type>, ParameterizedType>() {
          @Override public ParameterizedType map(Class<?> raw, List<Type> params) {
            return Types.newParameterizedType(raw, params);
          }
        });
  }

  /**
   * Parser for internal array class names such as {@code [Z}, {@code [[[Ljava.lang.String;} etc.
   *
   * <p>.java files can only use {@code int[]} format, not the internal format. But we have to
   * be able to parse from internal format because {@link Type#toString} can produce it.
   */
  private Parser<Class<?>> arrayClass() {
    Parser<Class<?>> arrayType = FQN.next(new Map<String, Parser<? extends Class<?>>>() {
      @Override public Parser<? extends Class<?>> map(String name) {
        // Only invoked when we already see a "[" at the beginning.
        Class<?> primitiveArray = PRIMITIVE_ARRAY_TYPES.get("[" + name);
        if (primitiveArray != null) return Parsers.constant(primitiveArray);
        if (name.startsWith("L") && name.endsWith(";")) {
          String className = name.substring(1, name.length() - 1);
          return Parsers.constant(Types.newArrayType(loadClass(className)));
        } else {
          return Parsers.expect("array class internal name");
        }
      }
    });
    return TERMS.token("[") // must be an array internal format from this point on.
        .next(arrayType.prefix(TERMS.token("[").retn(new Unary<Class<?>>() {
          @Override public Class<?> map(Class<?> componentType) {
            return Types.newArrayType(componentType);
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

  private static Parser<Type> wildcardType(Parser<Type> boundType) {
    return Parsers.or(
        TERMS.phrase("?", "extends").next(boundType).map(new Unary<Type>() {
          @Override public Type map(Type bound) { return Types.subtypeOf(bound); }
        }),
        TERMS.phrase("?", "super").next(boundType).map(new Unary<Type>() {
          @Override public Type map(Type bound) { return Types.supertypeOf(bound); }
        }),
        TERMS.token("?").retn(Types.subtypeOf(Object.class)));
  }

  private static ImmutableMap<String, Class<?>> mapByName(Class<?>... classes) {
    ImmutableMap.Builder<String, Class<?>> builder = ImmutableMap.builder();
    for (Class<?> cls : classes) {
      builder.put(cls.getName(), cls);
    }
    return builder.build();
  }
}
