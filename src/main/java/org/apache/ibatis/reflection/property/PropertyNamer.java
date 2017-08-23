/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.reflection.property;

import org.apache.ibatis.reflection.ReflectionException;

import java.util.Locale;

/**
 * @author Clinton Begin
 */
public final class PropertyNamer {

    private PropertyNamer() {
        // Prevent Instantiation of Static Class
    }

    /**
     * 根据方法名称获取其中的属性, 无论是getter还是setter.
     *
     * 整个方法还是比较简单,分两步走
     * 1)移除方法名中的set,get或者is.
     * 2) 对剩下的部分首字符小写, 但只有在第二个字符为小写的情况下才进行转换
     *  比如ABC这样的形式,是不会对第一个A进行小写转换的.
     *
     * 只支持三类方法,getXXX, isXXX或setXXX.
     * @param name 方法名
     * @return 属性
     */
    public static String methodToProperty(String name) {
        if (name.startsWith("is")) {
            name = name.substring(2);
        } else if (name.startsWith("get") || name.startsWith("set")) {
            name = name.substring(3);
        } else {
            throw new ReflectionException("Error parsing property name '" + name + "'.  Didn't start with 'is', 'get' or 'set'.");
        }

        if (name.length() == 1 || (name.length() > 1
                && !Character.isUpperCase(name.charAt(1)))) {
            //两种情况
            //1. name的长度为1,比如 getA, geta之类的,这个时候, name='a'.
            //2. name长度大于1(>=2), 且第二个字符不是大写.
            //比如getAbc, 或者getabc,  则name='abc'.

            name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
        } else {
            //剩下的情况只可能是getABC, 或者 getaBC, 或者getaBc 这样的情况,不做转化处理.
            //所以name保持原样
        }

        return name;
    }

    public static boolean isProperty(String name) {
        return name.startsWith("get") || name.startsWith("set") || name.startsWith("is");
    }

    public static boolean isGetter(String name) {
        return name.startsWith("get") || name.startsWith("is");
    }

    public static boolean isSetter(String name) {
        return name.startsWith("set");
    }

}
