package org.jparsec.java;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.codehaus.jparsec.error.ParserException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.google.common.reflect.TypeToken;

@RunWith(JUnit4.class)
@SuppressWarnings("serial")
public class TypeParserTest {

  @Test
  public void classNamesShouldDefaultToJavaLang() {
    assertEquals(Integer.class, new TypeParser().parse("Integer").getType());
    assertEquals(Void.class, new TypeParser().parse("Void").getType());
  }

  @Test
  public void fullyQualifiedClassName() {
    assertEquals(Integer.class, new TypeParser().parse("java.lang.Integer").getType());
    assertEquals(Void.class, new TypeParser().parse("java.lang.Void").getType());
    assertParser(TypeToken.of(TypeParserTest.class));
  }

  @Test
  public void primitiveTypeName() {
    assertEquals(void.class, new TypeParser().parse("void").getType());
    assertEquals(boolean.class, new TypeParser().parse("boolean").getType());
    assertEquals(byte.class, new TypeParser().parse("byte").getType());
    assertEquals(short.class, new TypeParser().parse("short").getType());
    assertEquals(int.class, new TypeParser().parse("int").getType());
    assertEquals(long.class, new TypeParser().parse("long").getType());
    assertEquals(float.class, new TypeParser().parse("float").getType());
    assertEquals(double.class, new TypeParser().parse("double").getType());
  }

  @Test
  public void primitiveArrayType() {
    assertEquals(int[].class, new TypeParser().parse("int[]").getType());
    assertEquals(int[].class, new TypeParser().parse(int[].class.getCanonicalName()).getType());
    assertEquals(int[][].class, new TypeParser().parse(int[][].class.getCanonicalName()).getType());
  }

  @Test
  public void genericArrayType() {
    assertParser(new TypeToken<Iterable<String>[]>() {});
    assertParser(new TypeToken<List<Iterable<String>[]>>() {});
    assertParser(new TypeToken<List<Iterable<int[]>[]>>() {});
  }

  @Test
  public void recursiveGenericType() {
    assertParser(new TypeToken<Enum<?>>() {});
  }

  @Test
  public void boundedWildcard() {
    assertEquals(new TypeToken<Bounded<?>>() {},
        new TypeParser().parse("org.jparsec.java.TypeParserTest$Bounded<?>"));
  }

  @Test
  public void nestedClassName() {
    assertEquals(Nested.class, new TypeParser().parse(Nested.class.getName()).getType());
  }

  @Test
  public void wildcardTypeWithNoBound() throws NoSuchFieldException {
    assertParser(new TypeToken<Iterable<?>>() {});
  }

  @Test
  public void wildcardTypeWithUpperBound() throws NoSuchFieldException {
    assertParser(new TypeToken<Iterable<? extends String>>() {});
  }

  @Test
  public void wildcardTypeWithLowerBound() throws NoSuchFieldException {
    assertParser(new TypeToken<Iterable<? super String>>() {});
  }

  @Test
  public void parameterizedTypeWithOneType() throws NoSuchFieldException {
    assertParser(new TypeToken<Iterable<String>>() {});
  }

  @Test
  public void parameterizedTypeWithTwoTypes() throws NoSuchFieldException {
    assertParser(new TypeToken<Map<?, ? extends Number>>() {});
  }

  @Test(expected = ParserException.class)
  public void primitiveTypeCannotBeUsedAsTypeParameter() throws NoSuchFieldException {
    new TypeParser().parse("Iterable<int>");
  }

  @Test(expected = ParserException.class)
  public void insufficientTypeParameters() throws NoSuchFieldException {
    new TypeParser().parse("java.util.Map<String>");
  }

  @Test(expected = ParserException.class)
  public void tooManyTypeParameters() throws NoSuchFieldException {
    new TypeParser().parse("java.util.List<String, ?>");
  }

  private static void assertParser(TypeToken<?> type) {
    assertEquals(type, new TypeParser().parse(type.toString()));
  }

  private interface Bounded<T extends Number> {}

  private static final class Nested {}
}
