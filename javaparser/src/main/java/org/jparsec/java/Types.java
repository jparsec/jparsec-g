package org.jparsec.java;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;

import com.google.common.reflect.AbstractInvocationHandler;
import com.google.common.reflect.Reflection;
import com.google.common.reflect.TypeResolver;

/**
 * Utility class to construct {@link Type} instances reflectively.
 *
 * <p>The crude implementations in this class don't bother implementing {@link Object#equals} and
 * {@link Object#hashCode} because it's tricky to get them right (especially to return hash
 * code consistent with JDK). As a work-around, we use Guava's {@link TypeResolver} to transform the
 * non-conformant type implementations to be standard-conformant, before returning them.
 */
public final class Types {

  /** Returns a wildcard type that's subtype of {@code bounds}. */
  public static WildcardType subtypeOf(Type... bounds) {
    TypeResolver resolver = new TypeResolver();
    final List<TypeVariable<?>> vars = new ArrayList<TypeVariable<?>>();
    int i= 0;
    for (Type bound : bounds) {
      TypeVariable<?> var = TypeVariableGenerator.freshTypeVariable("B" + (++i));
      vars.add(var);
      resolver = resolver.where(var, bound);
    }
    return (WildcardType) resolver.resolveType(new WildcardType() {
      @Override public Type[] getUpperBounds() {
        return vars.toArray(new Type[0]);
      }
      @Override public Type[] getLowerBounds() {
        return new Type[0];
      }
    });
  }

  /** Returns a wildcard type that's supertype of {@code bound}. */
  public static WildcardType supertypeOf(Type bound) {
    final TypeVariable<?> var = TypeVariableGenerator.freshTypeVariable("SUB");
    return (WildcardType) new TypeResolver().where(var, bound).resolveType(new WildcardType() {
      @Override public Type[] getLowerBounds() {
        return new Type[] {var};
      }
      @Override public Type[] getUpperBounds() {
        return new Type[] {Object.class};
      }
    });
  }

  /** Returns a parameterized type with {@code raw} and {@code typeArgs}. */
  public static ParameterizedType newParameterizedType(
      final Class<?> raw, List<? extends Type> typeArgs) {
    TypeResolver resolver = new TypeResolver();
    final List<TypeVariable<?>> vars = new ArrayList<TypeVariable<?>>();
    for (int i = 0; i < typeArgs.size(); i++) {
      // TODO: perform proper type inference and type checking.
      TypeVariable<?> var = TypeVariableGenerator.freshTypeVariable("T" + i);
      vars.add(var);
      resolver = resolver.where(var, typeArgs.get(i));
    }
    return (ParameterizedType) resolver.resolveType(new ParameterizedType() {
      @Override public Class<?> getRawType() {
        return raw;
      }
      @Override public Type getOwnerType() {
        return null;
      }
      @Override public Type[] getActualTypeArguments() {
        return vars.toArray(new Type[0]);
      }
    });
  }

  /** Returns a new array type with {@code componentType}. */
  public static Type newArrayType(Type componentType) {
    if (componentType instanceof Class<?>) {
      return Array.newInstance((Class<?>) componentType, 0).getClass();
    }
    final TypeVariable<?> var = TypeVariableGenerator.freshTypeVariable("E");
    return new TypeResolver().where(var, componentType).resolveType(new GenericArrayType() {
      @Override public Type getGenericComponentType() {
        return var;
      }
    });
  }

  private static final class TypeVariableGenerator<T> {
    private static final TypeVariable<?> PROTOTYPE =
        (TypeVariable<?>) TypeVariableGenerator.class.getTypeParameters()[0];

    /** Makes a fresh type variable that's only equal to itself. */
    @SuppressWarnings("unchecked")  // Delegates to the <T> of class Var except getName().
    static TypeVariable<Class<?>> freshTypeVariable(final String name) {
      // Use dynamic proxy so we only changes the behavior of getName() and equals/hashCode
      // Everything else delegates to a JDK native type variable.
      return Reflection.newProxy(TypeVariable.class, new AbstractInvocationHandler() {
        @Override protected Object handleInvocation(Object proxy, Method method, Object[] args)
            throws Throwable {
          if (method.getName().equals("getName")) {
            return name;
          }
          try {
            return method.invoke(PROTOTYPE, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        }
        @Override public String toString() {
          return name;
        }
      });
    }
  }

  private Types() {}
}
