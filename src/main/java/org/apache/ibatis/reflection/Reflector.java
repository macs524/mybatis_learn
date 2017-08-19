/**
 * Copyright 2009-2017 the original author or authors.
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

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * 一个类定义的各项数据的缓存对象. 或者说是缓存了定义类的元数据.
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * 一个Reflector对应了一个类的各项属性
 *
 * @author Clinton Begin
 */
public class Reflector {

    private final Class<?> type; // 对应的类的类型
    private final String[] readablePropertyNames; // 这个类所有可读的属性名数组
    private final String[] writeablePropertyNames; // 这个类所有可设置的属性名称数组.

    private final Map<String, Invoker> setMethods = new HashMap<String, Invoker>(); // setter 方法合集
    private final Map<String, Invoker> getMethods = new HashMap<String, Invoker>(); // get方法合集
    private final Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>(); // 设置类型映射合集
    private final Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>(); // 获取类型映射合集.

    //默认构造器
    private Constructor<?> defaultConstructor;

    //属性map
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

    /**
     * 构造函数, 每个Reflector都有一个对应的Reflector.
     * 记录了这个Class的一些反射信息
     * @param clazz clazz
     */
    public Reflector(Class<?> clazz) {
        type = clazz; //类类型

        addDefaultConstructor(clazz); // 添加默认构造函数.
        addGetMethods(clazz); // 添加getter方法
        addSetMethods(clazz); // 添加setter方法
        addFields(clazz); // 添加字段.

        // 所有可读的属性
        readablePropertyNames =
                getMethods.keySet().toArray(new String[getMethods.keySet().size()]);

        // 所有可写的属性
        writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);

        // 字段映射, 大写属性 -- 属性值.
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }

        for (String propName : writeablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    /**
     * 添加默认构造无参函数,
     * 那么如果没有无参构造函数这个当然就是NULL了. 不是必须的.
     * @param clazz 指定的类类型
     */
    private void addDefaultConstructor(Class<?> clazz) {
        Constructor<?>[] consts = clazz.getDeclaredConstructors();
        for (Constructor<?> constructor : consts) {
            if (constructor.getParameterTypes().length == 0) {
                if (canAccessPrivateMethods()) {
                    try {
                        constructor.setAccessible(true); //将可访问性置为true
                    } catch (Exception e) {
                        // Ignored. This is only a final precaution, nothing we can do.
                    }
                }
                if (constructor.isAccessible()) {
                    //如果构造函数可访问,则将其赋值给默认构造函数.
                    this.defaultConstructor = constructor;
                }
            }
        }
    }

    /**
     * 添加getter方法
     * @param cls 指定类
     */
    private void addGetMethods(Class<?> cls) {
        Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();

        //第一步,获取这个类及其父类上的所有的方法定义.得到一个数组.
        //而且可以保证这个数组中方法是不重复的.
        Method[] methods = getClassMethods(cls);

        //第二步,查找其中的getter方法
        for (Method method : methods) {

            //1. getter方法是不能有参数的
            if (method.getParameterTypes().length > 0) {
                continue;
            }
            String name = method.getName();

            //2. getter方法只能以get或is开头.
            if ((name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2)) {

                //作为一个getter方法, 需要是getXXX或者isXXX的形式
                //且方法不能是get(), 也不能是is()
                name = PropertyNamer.methodToProperty(name); //解析出属性名.

                addMethodConflict(conflictingGetters, name, method);
            }
        }

        //我们最终是要找到get的方法名和结果的一一对应关系,而conflictingGetters
        //里方法名和结果是一对多关系.所以需要再做处理
        //第三步,处理属性名和getter方法的一对多关系.
        resolveGetterConflicts(conflictingGetters);
    }

    /**
     * 处理属性名和方法的一对多关系, 处理getter方法.
     *
     * 看看这个是不是对上个版本进行了优化
     *
     * @param conflictingGetters 原一对多集合
     */
    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {

        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            Method winner = null; //胜出者


            String propName = entry.getKey(); // getter对应的属性名

            //方法列表, 至少有一条.
            for (Method candidate : entry.getValue()) {

                //1. 第一次遍历, winner 为 NULL,  则直接更新为当前方法即可.
                if (winner == null) {
                    winner = candidate;
                    continue;
                }

                //2. 第二种情况, 比较两个方法的返回值
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();

                if (candidateType.equals(winnerType)) {
                    // 如果两个方法的返回值类型一样,
                    // 那么只允许出现这种情况, 即一个是isAbc, 另外一个是getAbc
                    // 也就是getAbc, getabc, 这样的形式,且返回值为同样类型的话,这是不可接受的.
                    if (!boolean.class.equals(candidateType)) {
                        //侯选人的类型不为boolean. 不可接受.
                        // 只能是boolean, 不能是Boolean
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property "
                                        + propName + " in class " + winner.getDeclaringClass()
                                        + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                    } else if (candidate.getName().startsWith("is")) {
                        // 这个时候, 使用isAbc.
                        winner = candidate;
                    }
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    // 侯选方法的返回值类型是当前方法返回值类型的基类, 则保持当前不变
                    // OK getter type is descendant
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    // 更新为侯选人.
                    winner = candidate;
                } else {
                    // 两者不存在父子关系, 那这样是有问题的. 不处理.
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property "
                                    + propName + " in class " + winner.getDeclaringClass()
                                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
            addGetMethod(propName, winner);
        }
    }

    /**
     * 终极添加getter方法
     * 在getMethods里和getTypes里都添加.
     * @param name 属性名
     * @param method 对应的方法
     */
    private void addGetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            // 添加方法,这个好办
            getMethods.put(name, new MethodInvoker(method));
            //参数解析,对于泛型来说,是一个比较复杂的过程, 最终是根据当前类及实际使用的类型来解析出最终的值.
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            //解析最终的返回值类型.
            getTypes.put(name, typeToClass(returnType));
        }
    }

    /**
     * 添加setter方法,这个和添加getter方法有些类似
     *
     * @param cls 待处理的类
     */
    private void addSetMethods(Class<?> cls) {
        Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
        //1. 获取该类所有的方法
        Method[] methods = getClassMethods(cls);

        //2. 从中查找出所有的setter方法
        for (Method method : methods) {
            String name = method.getName();
            if (name.startsWith("set") && name.length() > 3) {
                if (method.getParameterTypes().length == 1) {

                    //setter方法仅限于方法名为setXXX且方法的参数个数为1
                    name = PropertyNamer.methodToProperty(name);

                    //对于setter来说,由于方法的返回值都是void
                    //所以只有当参数类型不一致的情况下,才可能出现同一个属性名有
                    //多个方法, 而且这个重载的可能性还比较高.
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }

        //3. 处理属性名和方法的一对多关系 .
        resolveSetterConflicts(conflictingSetters);
    }

    /**
     * 添加属性名和方法的映射关系.
     * 那这个有没有可能会导致同一个属性名有多个Method呢?
     *
     * 按说是有可能的,  比如有两个方法isAbc 和 getAbc, 以及getabc.
     * 那么属性都是abc, 而方法却各不一样
     * @param conflictingMethods 映射关系合集
     * @param name 属性名
     * @param method 方法.
     */
    private void addMethodConflict(Map<String, List<Method>> conflictingMethods,
                                   String name, Method method) {
        List<Method> list = conflictingMethods.get(name);
        if (list == null) {
            list = new ArrayList<Method>();
            conflictingMethods.put(name, list);
        }
        list.add(method);
    }

    /**
     * 处理setter方法的一对多关系
     * @param conflictingSetters 待处理的映射集合
     */
    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        for (String propName : conflictingSetters.keySet()) {

            //1. 找到该属性的所有method方法
            List<Method> setters = conflictingSetters.get(propName);

            //2. 找到该属性的getter类型.
            Class<?> getterType = getTypes.get(propName);

            //3. 开始查找
            Method match = null;
            ReflectionException exception = null;

            //4. 遍历所有的setter方法, 通过这次遍历,要么找到一个最合适的,
            //要么抛出异常.
            for (Method setter : setters) {

                //4.1 获取参数类型
                Class<?> paramType = setter.getParameterTypes()[0];
                //4.2 如果参数类型和属性对应的getter类型完全一致
                //那么不用说,肯定就是它了.
                if (paramType.equals(getterType)) {
                    // should be the best match
                    match = setter;
                    break;
                }
                if (exception == null) {
                    try {
                        //查找一个更匹配的setter方法
                        match = pickBetterSetter(match, setter, propName);
                    } catch (ReflectionException e) {
                        // there could still be the 'best match'
                        match = null;
                        exception = e;
                    }
                }
            }


            if (match == null) {
                throw exception;
            } else {
                addSetMethod(propName, match);
            }
        }
    }

    /**
     * 通过比较找出一个更合适的方法
     * @param setter1 方法1
     * @param setter2 方法2
     * @param property 参数
     * @return
     */
    private Method pickBetterSetter(Method setter1, Method setter2, String property) {

        //1. 如果之前没有设置过setter1, 则直接返回setter2
        if (setter1 == null) {
            return setter2;
        }

        //2. 获取这两个setter的入参类型
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];

        //3. 如果paramType1为超类,则返回setter2
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            //如果paramType2为超类,则返回方法1.
            return setter1;
        }
        throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
                + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
                + paramType2.getName() + "'.");
    }

    //添加setter方法和setter类型.
    private void addSetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            setMethods.put(name, new MethodInvoker(method));
            Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
            setTypes.put(name, typeToClass(paramTypes[0]));
        }
    }

    /**
     * 将类型转化为class. 这是最终的处理.
     * 把type转化为class.
     * @param src type
     * @return
     */
    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        if (src instanceof Class) {
            //如果刚好就是一个class, 即不是范型,这个比较好办,直接处理.
            result = (Class<?>) src;
        } else if (src instanceof ParameterizedType) {
            //如果是参数化的类型,也比较好办,取共原始类型
            result = (Class<?>) ((ParameterizedType) src).getRawType();
        } else if (src instanceof GenericArrayType) {
            //如果是数组,则取数组对应的原始数据
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) {
                //如果原始类型是Class,好办
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                //如果是Data<T>这类的也好办.
                Class<?> componentClass = typeToClass(componentType);
                result = Array.newInstance((Class<?>) componentClass, 0).getClass();
            }
        } else {
            // 有没有可能是其它类型,比如说Variable之类的. 应该是不可能.

            // 对于TypeVirable 的解析最终会返回一个实际的类或者ParameterizedType.
        }

        //至少是返回了一个Object.
        if (result == null) {
            result = Object.class;
        }
        return result;
    }

    /**
     * 添加字段, 注意和添加方法不同的是, 这里只处理当前类定义的所有字段
     * 不包括父类中的字段
     * @param clazz 类型.
     */
    private void addFields(Class<?> clazz) {
        //找到这个类声明的所有字段
        Field[] fields = clazz.getDeclaredFields();

        //遍历处理
        for (Field field : fields) {
            if (canAccessPrivateMethods()) {
                try {
                    field.setAccessible(true);
                } catch (Exception e) {
                    // Ignored. This is only a final precaution, nothing we can do.
                }
            }
            if (field.isAccessible()) {

                //不存在,且可访问
                if (!setMethods.containsKey(field.getName())) {
                    // issue #379 - removed the check for final because JDK 1.5 allows
                    // modification of final fields through reflection (JSR-133). (JGB)
                    // pr #16 - final static can only be set by the classloader
                    int modifiers = field.getModifiers();
                    if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                        //如果字段不是 final static 的, 就可以添加到setter.
                        addSetField(field);
                    }
                }
                if (!getMethods.containsKey(field.getName())) {
                    //getter一直可以添加
                    addGetField(field);
                }
            }
        }
        if (clazz.getSuperclass() != null) {
            //继续处理父类, 这是一个循环的过程.
            // 所以该类也有了父类的
            addFields(clazz.getSuperclass());
        }
    }

    /**
     * 添加setter方法到setMethod集合里
     * @param field 字段
     */
    private void addSetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            //解析的过程基本上理解了,
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 添加getter方法到getMethod集合里
     * @param field 字段
     */
    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 判断是否为有效的属性名
     * @param name
     * @return
     */
    private boolean isValidPropertyName(String name) {
        //不能以$开始,不能是serialVersionUID和class.
        return !(name.startsWith("$")
                || "serialVersionUID".equals(name)
                || "class".equals(name));
    }

    /**
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler Class.getMethods(),
     * because we want to look for private methods as well.
     *
     * 解释得很清楚了, 因为getMethods()虽然会返回超类的方法,但是它只统计那些public的方法.
     * 获得这个类及其超类的所有方法(无论是不是get, 无论是不是private)
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        Map<String, Method> uniqueMethods = new HashMap<>();
        Class<?> currentClass = cls;

        while (currentClass != null) {
            //注意这里用的是DeclaredMethods(), 这样会查找当前类
            //所定义的所有方法(无论是不是priveate)
            addUniqueMethods(uniqueMethods,
                    currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract
            Class<?>[] interfaces = currentClass.getInterfaces();

            for (Class<?> anInterface : interfaces) {
                // 同样要注意这里为什么用的是getMethods()呢?
                // 因为对于接口来说, 首先方法肯定都是public的
                // 所以getMethod()能够把当前接口自身定义的方法全找出来
                // 另外getMethod还会把父接口的所有方法也找出来.
                // 所以实际上来说是处理了父接口的.
                addUniqueMethods(uniqueMethods,
                        anInterface.getMethods());
            }

            currentClass = currentClass.getSuperclass(); // 继续处理其父类
        }

        Collection<Method> methods = uniqueMethods.values();

        return methods.toArray(new Method[methods.size()]);
    }

    /**
     * 添加方法到这个方法Map中
     *
     * @param uniqueMethods 方法map
     * @param methods 某个类(或接口)所声明的所有方法,任意访问符(public, protected, package,  private)
     *                长度可能为0.
     */
    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {

        //Map的映射关系是方法签名--对应方法.
        for (Method currentMethod : methods) {
            //只处理那些非桥的方法,因为桥方法是编译器自动生成的,而不是在源代码中存在的.
            //判断一个方法是否为桥方法就是看其是否有桥的修饰符,这个修饰符类似于public static 之类的,是一个标识位.
            if (!currentMethod.isBridge()) {
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                if (!uniqueMethods.containsKey(signature)) {
                    //只有不存在的时候才放. 如果存在,表示子类已重写该方法

                    //根据系统设置来判断是否可以访问私有方法.
                    if (canAccessPrivateMethods()) {
                        try {
                            currentMethod.setAccessible(true);
                        } catch (Exception e) {
                            // Ignored. This is only a final precaution, nothing we can do.
                        }
                    }

                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    /**
     * 获取某个方法的签名, 这个不考虑泛型
     * 签名字符串的构成为 returnType#methodName:param1,param2...,paramN
     * @param method 方法
     * @return 签名
     */
    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();

        //1. 返回值类型
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }

        //2. 方法名
        sb.append(method.getName());

        //3. 参数类型名,多个用, 分隔.
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * 能否访问私有方法, 这个是一个系统设置
     * 目前来看, 这个方法是返回true的
     * @return 能或者是不能
     */
    private static boolean canAccessPrivateMethods() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the name of the class the instance provides information for
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * 获取默认构造函数
     * @return
     */
    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    /**
     * 判断是否有默认构造函数
     * @return
     */
    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    /**
     * 查找某个指定的设置方法
     * @param propertyName 属性名(字段名)
     * @return
     */
    public Invoker getSetInvoker(String propertyName) {
        // 可以理解为Invoker是一个适配器.
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * 查找某个指定的getter 方法
     * @param propertyName
     * @return
     */
    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * Gets the type for a property setter
     *
     * @param propertyName - the name of the property
     * @return The Class of the propery setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets the type for a property getter
     *
     * @param propertyName - the name of the property
     * @return The Class of the propery getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets an array of the readable properties for an object
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /**
     * Gets an array of the writeable properties for an object
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writeablePropertyNames;
    }

    /**
     * Check to see if a class has a writeable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writeable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /**
     * Check to see if a class has a readable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    /**
     * 查找这个类是否有对应的属性
     * @param name 属性名
     * @return 结果
     */
    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
