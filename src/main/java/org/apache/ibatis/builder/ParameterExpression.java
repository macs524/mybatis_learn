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
package org.apache.ibatis.builder;

import java.util.HashMap;

/**
 * Inline parameter expression parser. Supported grammar (simplified):
 *
 * <pre>
 * inline-parameter = (propertyName | expression) oldJdbcType attributes
 * propertyName = /expression language's property navigation path/
 * expression = '(' /expression language's expression/ ')'
 * oldJdbcType = ':' /any valid jdbc type/
 * attributes = (',' attribute)*
 * attribute = name '=' value
 * </pre>
 *
 * 参数表达式
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class ParameterExpression extends HashMap<String, String> {

    private static final long serialVersionUID = -2417552199605158680L;

    public ParameterExpression(String expression) {
        parse(expression);
    }

    /**
     * 解析表达式， 比如说对于表达式item.id 来说，则p等于0
     * @param expression 表达式
     */
    private void parse(String expression) {
        int p = skipWS(expression, 0);
        if (expression.charAt(p) == '(') {
            //以括号开头为表达式，否则为属性
            expression(expression, p + 1);
        } else {
            //处理属性
            property(expression, p);
        }
    }

    private void expression(String expression, int left) {
        int match = 1;
        int right = left + 1;
        while (match > 0) {
            if (expression.charAt(right) == ')') {
                match--;
            } else if (expression.charAt(right) == '(') {
                match++;
            }
            right++;
        }
        put("expression", expression.substring(left, right - 1));
        jdbcTypeOpt(expression, right);
    }

    /**
     * 按属性进行解析
     * @param expression 表达式, 比如示例为 #{yn,jdbcType=INTEGER}
     * @param left 开始位置
     */
    private void property(String expression, int left) {
        if (left < expression.length()) {
            int right = skipUntil(expression, left, ",:");
            put("property", trimmedStr(expression, left, right)); //则property 为 yn, right为,的下标？
            jdbcTypeOpt(expression, right);
        }
    }

    /**
     * 过滤空白字符, 找到第一个非空节点的下标
     * @param expression 表达式
     * @param p 起始位置
     * @return 第一个非空字符的位置
     */
    private int skipWS(String expression, int p) {
        for (int i = p; i < expression.length(); i++) {
            if (expression.charAt(i) > 0x20) {
                return i;
            }
        }
        //表示全空白.
        return expression.length();
    }

    /**
     * 在表达式中从第P个位置开始，查找endChars的位置
     * @param expression 表达式
     * @param p 开始位置P
     * @param endChars 结束位置
     * @return
     */
    private int skipUntil(String expression, int p, final String endChars) {
        for (int i = p; i < expression.length(); i++) {
            char c = expression.charAt(i);
            //对表达式中的每一个字符进行判断，找到则返回该下标
            if (endChars.indexOf(c) > -1) {
                return i; //表示第I个字符是一个特殊字符
            }
        }

        //返回整个表达式的长度
        return expression.length();
    }

    /**
     * 从指定位置解析表达式中的jdbcType
     * @param expression
     * @param p
     */
    private void jdbcTypeOpt(String expression, int p) {
        p = skipWS(expression, p);
        if (p < expression.length()) {

            //正常来说，第P们不是:就是,如果有其它类型，那么就有问题了
            if (expression.charAt(p) == ':') {
                jdbcType(expression, p + 1);
            } else if (expression.charAt(p) == ',') {
                option(expression, p + 1);
            } else {
                throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
            }
        }
    }

    /**
     * 处理JDBC
     * @param expression
     * @param p
     */
    private void jdbcType(String expression, int p) {
        int left = skipWS(expression, p);
        int right = skipUntil(expression, left, ",");
        if (right > left) {
            put("jdbcType", trimmedStr(expression, left, right));
        } else {
            throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
        }
        option(expression, right + 1);
    }

    private void option(String expression, int p) {
        int left = skipWS(expression, p);
        if (left < expression.length()) {
            int right = skipUntil(expression, left, "=");
            String name = trimmedStr(expression, left, right);
            left = right + 1;
            right = skipUntil(expression, left, ",");
            String value = trimmedStr(expression, left, right);
            put(name, value);
            option(expression, right + 1);
        }
    }

    /**
     * 去空格和非打印字符
     * @param str
     * @param start
     * @param end
     * @return
     */
    private String trimmedStr(String str, int start, int end) {
        while (str.charAt(start) <= 0x20) {
            start++;
        }
        while (str.charAt(end - 1) <= 0x20) {
            end--;
        }
        return start >= end ? "" : str.substring(start, end);
    }


    public static void main(String args[]) {
        System.out.println("ok");
    }
}
