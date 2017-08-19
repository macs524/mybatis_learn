/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * 如果说一个Reflector对应了一个Class,
 * 那么一个MetaClass 也对应了一个Class, 可以理解为是对Reflector的进一步封装.
 *
 * 这个类基本上搞定了, 这一次封装之后, 主要是增加了对于属性表达式的支持.
 * @author Clinton Begin
 */
public class MetaClass {

  private final ReflectorFactory reflectorFactory;
  private final Reflector reflector;

  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    this.reflector = reflectorFactory.findForClass(type);
  }

    /**
     * 只能通过静态方法进行调用
     * @param type type
     * @param reflectorFactory  reflectorFactory 构造方法
     * @return 创建的MetaClas对象
     */
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

    /**
     * 为某个属性对应的类创建一个对应的MetaClass对象
     * @param name
     * @return
     */
  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name);
    return MetaClass.forClass(propType, reflectorFactory);
  }

    /**
     *
     * @param name
     * @return
     */
  public String findProperty(String name) {
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

    /**
     * 替换"_" 为 "", 其它和findProperty一致.
     * @param name
     * @param useCamelCaseMapping
     * @return
     */
  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

    /**
     * getter属性
     * @return
     */
  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

    /**
     * setter属性集
     * @return
     */
  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

    /**
     * 某个属性的setter类型,  同样支持属性表达式. 可以递归处理.
     * @param name
     * @return
     */
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

    /**
     * 某个属性的getter 类型, 支持属性表达式
     *
     * 特别的, 对于 如果getter的List<XXX> xxxList 这样的形式的话
     * 返回的是XXX
     * @param name
     * @return
     */
  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);
  }



  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

    /**
     * 获取属性的getter方法
     *
     * getterType 的获取基本上理解了,但是为什么setter没有做这样的处理呢?
     * @param prop
     * @return
     */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    Class<?> type = reflector.getGetterType(prop.getName());

    //如果它是一个集合, 比如是一个List<String>
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {

        // 为什么会有这样的限制, 因为对于item[0] 来说, item[0]如果是一个Array, OK, 那没有问题
        // 但这样也可以表示item是一个List, 为什么不可以呢.

        // 但根据之前的分析, 前面的type 一定是List, 而不是String
        // 所以这个方法的目的,就是对于这种情况, 返回String 而不是返回List, 以便于属性的继续处理.
        // 而再次通过这个方法调用之后, retrnType则一定对应的是List<String>
      Type returnType = getGenericGetterType(prop.getName());

      if (returnType instanceof ParameterizedType) {
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();

          // 找到实际类型,
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          returnType = actualTypeArguments[0]; //  这个时候的returnType, 则代表了实际类型, 比如String.
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
              // 当然有可能还是泛型, 比如List<List<String>>, 那么type则是List<String>
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }


    return type;
  }

    /**
     * 根据属性名查找其getter返回类型. 比如List<String> dataList.
     *
     * 这个方法会返回List<String>, 而通常的方法都只能返回 List
     *
     *
     * @param propertyName
     * @return
     */
  private Type getGenericGetterType(String propertyName) {
    try {

      Invoker invoker = reflector.getGetInvoker(propertyName);

      if (invoker instanceof MethodInvoker) {
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        Field field = (Field) _field.get(invoker);
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException e) {
    } catch (IllegalAccessException e) {
    }
    return null;
  }

  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasSetter(prop.getName());
    }
  }

  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasGetter(prop.getName());
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

    /**
     * 查找某个属性, 会依次检查属性表达式是否正确
     *
     * 比如假设当前对应的类是User.
     * 其定义如下:
     *  class User {
     *      Address[] addrs;
     *  }
     *
     *  class Address {
     *      String citeName
     *  }
     *
     *
     *  则表达式 addrs[0].cityName, 会转化为 addrs.cityName
     *
     *  这个过程, 先是会解析出属性addrs, 然后得到其对应的类型Address
     *  再去解析看Address有没有cityName.
     *
     * @param name 属性名
     * @param builder builder.
     * @return
     */
  private StringBuilder buildProperty(String name, StringBuilder builder) {
    PropertyTokenizer prop = new PropertyTokenizer(name);

    //其实是一个递归的过程
    if (prop.hasNext()) {
        //
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        builder.append(propertyName);
        builder.append(".");


        MetaClass metaProp = metaClassForProperty(propertyName);
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else {

        // 递归结束的时候会调用这个方法. 但感觉这样会不会有问题 ? 如果是name = item[0]?
        // 不过如果就是属性的话, 没有必要后面加[], 加了不识别也是很正常的.
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

    /**
     * 是否有构造函数
     * @return
     */
  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
