/**
 * Copyright 2009-2016 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.*;
import java.util.Arrays;

/**
 * @author Iwao AVE!
 */
public class TypeParameterResolver {

    /**
     * @return The field type as {@link Type}.
     * If it has type parameters in the declaration,<br>
     *   they will be resolved to the actual runtime {@link Type}s.
     */
    public static Type resolveFieldType(Field field, Type srcType) {
        return resolveType(field.getGenericType(),
                srcType,
                field.getDeclaringClass());
    }

    /**
     *
     *
     *
     * 解析指定方法的返回类型,但有个问题, 为什么不直接使用method.getGenericReturnType()呢?
     * @param method 指定的方法
     * @param srcType 方法所属的类实例
     * @return The return type of the method as {@link Type}.
     * If it has type parameters in the declaration,<br>
     * they will be resolved to the actual runtime {@link Type}s.
     */
    public static Type resolveReturnType(Method method, Type srcType) {

        //交由另一个方法处理 , 以下的方法需要三个参数,分别如下:

        // 1. 方法通用的返回类型
        // 2. 所在类型, 在mybatis的调用里, 其实这个就是method所在的类
        // 3. 声明该方法所在的类, 其实和第二个参数是一样的,只不过第二个参数Type是class的父类.
        return resolveType(method.getGenericReturnType(),
                srcType,
                method.getDeclaringClass());
    }

    /**
     * @return The parameter types of the method as an array of {@link Type}s. If they have type parameters in the declaration,<br>
     *         they will be resolved to the actual runtime {@link Type}s.
     */
    public static Type[] resolveParamTypes(Method method, Type srcType) {
        Type[] paramTypes = method.getGenericParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();
        Type[] result = new Type[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            result[i] = resolveType(paramTypes[i], srcType, declaringClass);
        }
        return result;
    }

    /**
     * 解析返回值类型, 本方法实际上只处理type带有泛型的情况
     *
     * 如果type是普通的class, 如Integer, String之类不带泛型, 并不会处理.
     *
     * 对于带泛型, 则需要根据type的类型分别进行处理.
     *
     *
     * @param type 类型
     * @param srcType 原始类型, 就是目标类型type所属的类的type.
     * @param declaringClass 声明class
     * @return 解析之后的类型
     */
    private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {

        //由于在字段,参数,返回值的泛型定义不可能是WildcardType类型,
        //所以只需要考虑TypeVariable,  ParameterizedType 和 GenericArrayType
        //如果这个类本身就是一个class类型,则不解析.
        if (type instanceof TypeVariable) {
            return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
        } else if (type instanceof ParameterizedType) {
            return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
        } else if (type instanceof GenericArrayType) {
            return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
        } else {
            return type;
        }
    }

    private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType, Class<?> declaringClass) {
        Type componentType = genericArrayType.getGenericComponentType();
        Type resolvedComponentType = null;
        if (componentType instanceof TypeVariable) {
            resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType, declaringClass);
        } else if (componentType instanceof GenericArrayType) {
            resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType, srcType, declaringClass);
        } else if (componentType instanceof ParameterizedType) {
            resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType, srcType, declaringClass);
        }
        if (resolvedComponentType instanceof Class) {
            return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
        } else {
            //可能会继续返回数组类型.
            return new GenericArrayTypeImpl(resolvedComponentType);
        }
    }

    /**
     * 对参数化的对象进行解析,结果还是返回了一个参数化类型
     * 那么我们要明确这个解析主要做了些什么事.
     *
     * 从实现的角度来说, 主要是为了处理其对应的泛型参数.
     * 比如说,对于一个参数化的类型, 有可能有以下几种情况
     * List<T>为例,
     *  1) List<String>, 这是最简单的. 参数String 是一个Class类型,不用处理.
     *  2) List<T>, 这种情况下, 要对T做进一步的处理
     *
     *
     * @param parameterizedType 待解析的对象
     * @param srcType 源type
     * @param declaringClass 声明方法的类
     * @return
     */
    private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType, Type srcType, Class<?> declaringClass) {

        //假设我们要解析的返回值类型是Map<String, Integer>
        //1. 找到其原始类型, 这里的原始类型为Map.class
        Class<?> rawType = (Class<?>) parameterizedType.getRawType();

        //2. 参数列表, 如果是Map<String, Integer>, 则这里的参数列表为String, Integer
        // 也就是说, 这里的type[] 数组实际上是class实例.
        Type[] typeArgs = parameterizedType.getActualTypeArguments();
        Type[] args = new Type[typeArgs.length];
        for (int i = 0; i < typeArgs.length; i++) {
            if (typeArgs[i] instanceof TypeVariable) {
                args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
            } else if (typeArgs[i] instanceof ParameterizedType) {
                args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType, declaringClass);
            } else if (typeArgs[i] instanceof WildcardType) {
                args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
            } else {
                args[i] = typeArgs[i]; // 为class, 不处理.
            }
        }

        // 对于这种类型来说
        return new ParameterizedTypeImpl(rawType, null, args);
    }

    private static Type resolveWildcardType(WildcardType wildcardType, Type srcType, Class<?> declaringClass) {
        Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType, declaringClass);
        Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType, declaringClass);
        return new WildcardTypeImpl(lowerBounds, upperBounds);
    }

    private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType, Class<?> declaringClass) {
        Type[] result = new Type[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i] instanceof TypeVariable) {
                result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
            } else if (bounds[i] instanceof ParameterizedType) {
                result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType, declaringClass);
            } else if (bounds[i] instanceof WildcardType) {
                result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
            } else {
                result[i] = bounds[i];
            }
        }
        return result;
    }

    /**
     * 解析返回类型为泛型变量的实际数据类型
     *
     * 比如说 T getData(), 那么这个方法的目标就是通过一切已知的信息
     * 将这个泛型转化为一个实际的类
     *
     * @param typeVar 返回值类型
     * @param srcType 原始类型
     * @param declaringClass 声明class
     * @return 解析之后的结果,那么解析是要做什么?
     */
    private static Type resolveTypeVar(TypeVariable<?> typeVar,
                                       Type srcType, Class<?> declaringClass) {
        Type result = null;
        Class<?> clazz = null;

        //原始类型只能是Class或者是带泛型的ParameterizedType, 因为只有它们能找到一个类.
        if (srcType instanceof Class) {
            clazz = (Class<?>) srcType;
        } else if (srcType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) srcType;
            clazz = (Class<?>) parameterizedType.getRawType();
        } else {
            throw new IllegalArgumentException("The 2nd arg must be Class or ParameterizedType, " +
                    "but was: " + srcType.getClass());
        }

        if (clazz == declaringClass) {
            //极有可能,在这里就结束了
            Type[] bounds = typeVar.getBounds();
            if (bounds.length > 0) {
                return bounds[0]; // 如果有上界,那么上界肯定是一个确定的类型
                //可能是Integer, 也可能是List<Integer>, 但至少这个是可以确定的.
            }
            return Object.class;
        }


        // 这主要是对于某个子类来自父类的字段的情况.
        //如果clazz 不等于声明的 declaringClass, 则查找其父类
        // 就mybatis内部调用来说不会出现这个问题,但
        Type superclass = clazz.getGenericSuperclass();
        result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
        if (result != null) {
            return result;
        }

        Type[] superInterfaces = clazz.getGenericInterfaces();
        for (Type superInterface : superInterfaces) {
            result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
            if (result != null) {
                return result;
            }
        }
        return Object.class;
    }

    /**
     * 查找父类
     * @param typeVar 变量定义
     * @param srcType 变量解析的开始位置
     * @param declaringClass 声明该变量的方法或字段的类
     * @param clazz srcType对应的clazz
     * @param superclass clazz的父类型.
     * @return
     */
    private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass, Class<?> clazz, Type superclass) {
        Type result = null;
        if (superclass instanceof ParameterizedType) {
            // 作为一个类定义来说,应该可能是Class<T>或者是Class.
            ParameterizedType parentAsType = (ParameterizedType) superclass;
            Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
            if (declaringClass == parentAsClass) {
                //重点是这个,子父类关系.
                //类型上的实际参数(T,T)
                Type[] typeArgs = parentAsType.getActualTypeArguments();
                //类上的泛型参数(K, V)
                TypeVariable<?>[] declaredTypeVars = declaringClass.getTypeParameters();

                //这个泛型变量一定存在于declaredTypeVars中, 所以 要遍历的是declaredTypeVars
                for (int i = 0; i < declaredTypeVars.length; i++) {
                    if (declaredTypeVars[i] == typeVar) {
                        if (typeArgs[i] instanceof TypeVariable) {
                            TypeVariable<?>[] typeParams = clazz.getTypeParameters();
                            for (int j = 0; j < typeParams.length; j++) {
                                if (typeParams[j] == typeArgs[i]) {
                                    if (srcType instanceof ParameterizedType) {
                                        result = ((ParameterizedType) srcType).getActualTypeArguments()[j];
                                    }
                                    break;
                                }
                            }
                        } else {
                            result = typeArgs[i];
                        }
                    }
                }
            } else if (declaringClass.isAssignableFrom(parentAsClass)) {
                //继续向上解析,因为类不匹配.
                result = resolveTypeVar(typeVar, parentAsType, declaringClass);
            }
        } else if (superclass instanceof Class) {
            if (declaringClass.isAssignableFrom((Class<?>) superclass)) {
                //以superclass为起点,继续解析.
                result = resolveTypeVar(typeVar, superclass, declaringClass);
            }
        }
        return result;
    }

    private TypeParameterResolver() {
        super();
    }

    /**
     * 对于参数类型的具体表示.
     */
    static class ParameterizedTypeImpl implements ParameterizedType {
        private Class<?> rawType;

        private Type ownerType;

        private Type[] actualTypeArguments;

        /**
         * 惟一构造函数
         * @param rawType 原始类型
         * @param ownerType 类所有者
         * @param actualTypeArguments 实际参数列表.
         */
        public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
            super();
            this.rawType = rawType;
            this.ownerType = ownerType;
            this.actualTypeArguments = actualTypeArguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public String toString() {
            return "ParameterizedTypeImpl [rawType=" + rawType + ", ownerType=" + ownerType + ", actualTypeArguments=" + Arrays.toString(actualTypeArguments) + "]";
        }
    }

    static class WildcardTypeImpl implements WildcardType {
        private Type[] lowerBounds;

        private Type[] upperBounds;

        private WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
            super();
            this.lowerBounds = lowerBounds;
            this.upperBounds = upperBounds;
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds;
        }

        @Override
        public Type[] getUpperBounds() {
            return upperBounds;
        }
    }

    static class GenericArrayTypeImpl implements GenericArrayType {
        private Type genericComponentType;

        private GenericArrayTypeImpl(Type genericComponentType) {
            super();
            this.genericComponentType = genericComponentType;
        }

        @Override
        public Type getGenericComponentType() {
            return genericComponentType;
        }
    }
}
