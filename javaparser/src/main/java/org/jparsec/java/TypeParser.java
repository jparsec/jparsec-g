package org.jparsec.java;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import org.codehaus.jparsec.Parser;
import org.codehaus.jparsec.Parsers;
import org.codehaus.jparsec.Scanners;
import org.codehaus.jparsec.Terminals;
import org.codehaus.jparsec.error.ParserException;
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

  private static final Parser<?> WORD = Patterns.isChar(Character::isJavaIdentifierStart)
      .next(Patterns.isChar(c -> c == ';' || Character.isJavaIdentifierPart(c)).many())
      .toScanner("identifier");

  private static final Terminals TERMS = Terminals
      .operators("<", ">", "&", ",", "[", "]", "?", "@", ".")
      .words(WORD.source())
      .keywords("extends", "super")
      .build();

  private static final Parser<String> FQN = Terminals.identifier()
      .sepBy1(TERMS.token("."))
      .map(Joiner.on('.')::join);

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
  public TypeToken<?> parse(String string) throws ParserException {
    Parser.Reference<Type> ref = Parser.newReference();
    Parser<Type> type = Parsers.or(
        wildcardType(ref.lazy()), parameterizedType(ref.lazy()), arrayClass(), rawType());
    ref.set(type.postfix(TERMS.phrase("[", "]").retn(Types::newArrayType)));
    return TypeToken.of(
        ref.get().from(TERMS.tokenizer(), Scanners.WHITESPACES.optional()).parse(string));
  }

  private Parser<Class<?>> rawType() {
    return FQN.map(name -> {
        Class<?> primitiveType = PRIMITIVE_TYPES.get(name);
        if (primitiveType != null) return primitiveType;
        return loadClass(name.indexOf('.') < 0 ? "java.lang." + name : name);
      });
  }

  private Parser<ParameterizedType> parameterizedType(Parser<Type> typeArg) {
    return Parsers.sequence(
        rawType(),
        Parsers.between(TERMS.token("<"), typeArg.sepBy(TERMS.token(",")), TERMS.token(">")),
        Types::newParameterizedType);
  }

  /**
   * Parser for internal array class names such as {@code [Z}, {@code [[[Ljava.lang.String;} etc.
   *
   * <p>.java files can only use {@code int[]} format, not the internal format. But we have to
   * be able to parse from internal format because {@link Type#toString} can produce it.
   */
  private Parser<Class<?>> arrayClass() {
    Parser<Class<?>> arrayType = FQN.next(name -> {
        // Only invoked when we already see a "[" at the beginning.
        Class<?> primitiveArray = PRIMITIVE_ARRAY_TYPES.get("[" + name);
        if (primitiveArray != null) return Parsers.constant(primitiveArray);
        if (name.startsWith("L") && name.endsWith(";")) {
          String className = name.substring(1, name.length() - 1);
          return Parsers.constant(Types.newArrayType(loadClass(className)));
        } else {
          return Parsers.expect("array class internal name");
        }
      });
    return TERMS.token("[") // must be an array internal format from this point on.
        .next(arrayType.prefix(TERMS.token("[").retn(Types::newArrayType)));
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
        TERMS.phrase("?", "extends").next(boundType).map(Types::subtypeOf),
        TERMS.phrase("?", "super").next(boundType).map(Types::supertypeOf),
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
