/**
 * Copyright 2009-2016 the original author or authors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.parsing;

/**
 * 通用Token处理器, 其核心方法是解析占位符,占位符由openToken和closeToken.
 *
 * 举例来说,通常我们会在xml的某些配置中使用${xxx}
 * 如 <property name="username" value="${sql.username}" />
 *
 * 那么这个类的目的就是对指定的字符串进行解析,解析其中的占位符
 * 解析出来之后,用指定的tokenHandler去处理.
 * @author Clinton Begin
 */
public class GenericTokenParser {

    /**
     * 所以这个类必须要有两类属性, 占位符定义和指定的token处理器.
     */
    private final String openToken; //占位符的开始位置
    private final String closeToken; //占位符的结束位置
    private final TokenHandler handler; //token处理器

    /**
     * 构造
     * @param openToken token开始标记
     * @param closeToken token结束标记
     * @param handler token处理器
     */
    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    /**
     * 解析, 主要业务逻辑如下:
     * 1)解析text中是否存在指定的token, 什么是指定的token
     *   比如,如果 openToken='${', closeToken='}'
     *   则主要是解析text中有没有${xxx} 这样的token
     * 2) 如果有, 则将解析出来的token存储到expression变量中去,交给handler处理
     *   不同的handler, 处理方式不一样.
     *   比如,我们前面看到的变量替换,其handler的处理方式是将expression作为key,去变量集中查找是否有其对应的value.
     *   没有则原封不动的返回.
     *
     *   但对于 DynamicCheckerTokenParser 来说,只是将内部的标记置为true, 表示这是一个动态sql
     *   然而返回的却是null
     *
     * @param text 待处理的文本
     * @return 处理后的结果
     */
    public String parse(String text) {

        //1. 判断text是否为空.
        if (text == null || text.isEmpty()) {
            return "";
        }
        char[] src = text.toCharArray();
        int offset = 0;

        // 查找占位符的起始位置, 从0开始查起.
        int start = text.indexOf(openToken);

        //如果没有找到,说明文本中没有有效的占位符,不处理.
        if (start == -1) {
            return text;
        }

        //builder表示解析之后的text内容. 很可能是原样输出.
        final StringBuilder builder = new StringBuilder();
        StringBuilder expression = null;


        while (start > -1) {
            if (start > 0 && src[start - 1] == '\\') {
                // 这种情况为\${, 那么这种情况下,start至少也是1,所以要限制start > 0.
                // this open token is escaped. remove the backslash and continue.
                //之所以减1, 就是移除转义符\
                builder.append(src, offset, start - offset - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                //因为start > -1, 所以这里start至少是0. 那么
                // found open token. let's search close token.
                if (expression == null) {
                    expression = new StringBuilder();
                } else {
                    expression.setLength(0);
                }
                builder.append(src, offset, start - offset); // 取src数组中的一段字符串
                //offset是开始位置, start - offset 是字符串的个数.

                offset = start + openToken.length(); //跳过转义开始字符.
                //继续查找结束的标记
                int end = text.indexOf(closeToken, offset);

                //那么有三种情况
                while (end > -1) {
                    //情况1, 找到了,
                    // 根据上一个text.indexOf(closeToken, offset)
                    // 则end如果不是-1的话,肯定是不小于offset的.
                    //又分为两种情况, 是转义,或者是非转义.
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        // 为什么要放在表达式里, 相当于这个}就是一个普通的字符串了, 虽然这个无效
                        // 但有可能还能找到下一个有效的表达式.
                        // 比如说 This is ${my\}name}, 那么这个表达式会先设置为my},
                        // 经过算法处理, 如果end找到, 则expression的表达式最终会是 my}name.
                        // 如果这里不存储的话,结果将是name,肯定是有问题的.
                        expression.append(src, offset, end - offset - 1).append(closeToken);
                        //跳过处理
                        offset = end + closeToken.length();

                        // 继续找
                        end = text.indexOf(closeToken, offset);
                    } else {
                        //end = offset.  那这个就比较明显了,表达式为空.
                        //end > offset, 且src[end-1]不等于\, 说明找到了一个满足条件的表达式.
                        //所以要退出.
                        //存储表达式. 表达式一定是从offset起,已经跳过了开始标签符
                        //第二个参数是表示append的长度, end - offset 的值, 正好是表达式里的内容
                        // 即对于${user.name}来说, expression的值一定是user.name
                        expression.append(src, offset, end - offset);
                        //offset = end + closeToken.length(); 这里可以不设置 //#1
                        break; // 如果这里退出,很明显, end 还是 > -1 的.
                    }
                }

                //如果找到了开始标记但是没有结束标记, 则offset一步到位, 标记为数组长度,则下一次解析就结束 了.

                //进入这里,很明显,没有找到. 或者是找到了,但是转义符.
                //#1, 因为无论这两个分支哪个最终匹配, offset都会重新设置.
                if (end == -1) {
                    // close token was not found.
                    // 把剩下的这一部分添加到builder中去.
                    builder.append(src, start, src.length - start);
                    offset = src.length;
                } else {
                    //这种情况肯定是从前面的while循环中跳出来的,在跳出来之前已经构造了表达式,直接处理即可.
                    // 用指定的处理器来处理, 并返回处理后的字符串.
                    builder.append(handler.handleToken(expression.toString()));
                    offset = end + closeToken.length();
                }
            }

            //找下一个openToken, 如果offset=src.length, 则start的值肯定为-1.
            start = text.indexOf(openToken, offset);

            // 如果start==-1, 则表示找不到开始转义符了,整个查找过程可以结束.
        }//while (start > -1)


        if (offset < src.length) {
            //把剩下的部分加进来
            builder.append(src, offset, src.length - offset);
        }
        return builder.toString();
    }
}
