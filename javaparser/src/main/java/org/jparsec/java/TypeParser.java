package org.jparsec.java;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jparsec.Parser;
import org.codehaus.jparsec.Parsers;
import org.codehaus.jparsec.Scanners;
import org.codehaus.jparsec.Terminals;
import org.codehaus.jparsec.Tokens.Tag;
import org.codehaus.jparsec.functors.Map;
import org.codehaus.jparsec.functors.Map2;
import org.codehaus.jparsec.pattern.CharPredicate;
import org.codehaus.jparsec.pattern.Patterns;

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
      SINGLE_IDENTIFIER.sepBy(Scanners.isChar('.')).source(),
      new String[] {"<", ">", "&", ",", "[", "]", "?", "@"},
      new String[] {
          "extends", "super",
          "void", "boolean", "byte", "short", "int", "long", "float", "double"});

  private static final Parser<Class<?>> PRIMITIVE_TYPE_PARSER = Parsers.or(
      TERMS.token("void").retn(void.class),
      TERMS.token("boolean").retn(boolean.class),
      TERMS.token("byte").retn(byte.class),
      TERMS.token("short").retn(short.class),
      TERMS.token("int").retn(int.class),
      TERMS.token("long").retn(long.class),
      TERMS.token("float").retn(float.class),
      TERMS.token("double").retn(double.class));

  private final Parser<Class<?>> classParser;

  public TypeParser() {
    this(TypeParser.class.getClassLoader());
  }

  /** Create a type parser with {@code classloader} used to load classes. */
  public TypeParser(final ClassLoader classloader) {
    checkNotNull(classloader);
    this.classParser = Terminals.fragment(Tag.IDENTIFIER).map(
        new Map<String, Class<?>>() {
          @Override public Class<?> map(String name) {
            if (name.indexOf('.') < 0) {
              name = "java.lang." + name;
            }
            try {
              return Class.forName(name, false, classloader);
            } catch (ClassNotFoundException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }

  /** Parses {@code str} to a {@link TypeToken}. */
  public TypeToken<?> parse(String str) {
    Parser.Reference<Type> ref = Parser.newReference();
    Parser<Type> componentTypeParser = Parsers.or(
        PRIMITIVE_TYPE_PARSER,
        parameterizedTypeParser(classParser, ref.lazy()),
        classParser);
    ref.set(arrayTypeParser(componentTypeParser));
    return TypeToken.of(
        ref.get().from(TERMS.tokenizer(), Scanners.WHITESPACES.optional()).parse(str));
  }

  private static Parser<Type> arrayTypeParser(Parser<Type> typeParser) {
    return typeParser.postfix(TERMS.phrase("[", "]").retn(new Map<Type, Type>() {
      @Override public Type map(Type componentType) {
        return Types.newArrayType(componentType);
      }
    }));
  }

  private static Parser<Type> typeParameterParser(Parser<Type> typeParser) {
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

  private static Parser<ParameterizedType> parameterizedTypeParser(
      Parser<Class<?>> classParser, Parser<Type> typeArgParser) {
    return Parsers.sequence(
        classParser,
        typeParameterParser(typeArgParser)
            .sepBy(TERMS.token(","))
            .between(TERMS.token("<"), TERMS.token(">")),
        new Map2<Class<?>, List<Type>, ParameterizedType>() {
          @Override public ParameterizedType map(Class<?> raw, List<Type> params) {
            checkArgument(raw.getTypeParameters().length == params.size(),
                "%s expected %s type parameters, while %s are provied",
                raw, raw.getTypeParameters().length, params);
            return Types.newParameterizedType(raw, params);
          }
        });
  }
}
