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
package org.apache.ibatis.builder.annotation;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

  private final SqlSourceBuilder sqlSourceParser;
  private final Class<?> providerType;
  private Method providerMethod;
  private String[] providerMethodArgumentNames;

    /**
     * 这是一个新面孔
     * @param config 配置
     * @param provider 注解, 四大Provider注解之一
     */
  public ProviderSqlSource(Configuration config, Object provider) {
    String providerMethodName;
    try {

        this.sqlSourceParser = new SqlSourceBuilder(config);

        //一个示范的定义如下:
        /*
         @SelectProvider(type = StatementProvider.class, method = "provideSelect")
         S select(S param);

         public class StatementProvider {
            public String provideSelect(Object param) {}
            ...
         }

         */

        //可见,这里的type就是指的对应的提供了SQl语句的类
      this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
        //方法名则是providerType中的某个方法
      providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);

      for (Method m : this.providerType.getMethods()) {
        if (providerMethodName.equals(m.getName())) {

            //因为我们需要一个SQL,所以该方法的返回值必须是string
          if (m.getReturnType() == String.class) {
            if (providerMethod != null){
                //不能有多条
              throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                      + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                      + "'. Sql provider method can not overload.");
            }
            this.providerMethod = m;
            this.providerMethodArgumentNames = new ParamNameResolver(config, m).getNames(); //之所以解析参数，是因为要执行方法。
          }
        }
      }
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
    }
    if (this.providerMethod == null) {
      throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
          + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
    }
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    SqlSource sqlSource = createSqlSource(parameterObject);
    return sqlSource.getBoundSql(parameterObject);
  }

    /**
     * 主要是生成SQL, 而生成SQL直接的方式就是调用该对应的相应方法,并传入参数.
     * @param parameterObject
     * @return
     */
  private SqlSource createSqlSource(Object parameterObject) {
    try {
        //假定它是存在的,如果不存在就尴尬了.
      Class<?>[] parameterTypes = providerMethod.getParameterTypes();
      String sql;

      if (parameterTypes.length == 0) {
          //如果没有任何参数,则直接执行即可.
        sql = (String) providerMethod.invoke(providerType.newInstance());
      } else if (parameterTypes.length == 1 &&
              (parameterObject == null || parameterTypes[0].isAssignableFrom(parameterObject.getClass()))) {
          //一个参数
        sql = (String) providerMethod.invoke(providerType.newInstance(), parameterObject);
      } else if (parameterObject instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parameterObject;
          //一个Map, 从Map中抽取参数.
        sql = (String) providerMethod.invoke(providerType.newInstance(), extractProviderMethodArguments(params, providerMethodArgumentNames));
      } else {
        throw new BuilderException("Error invoking SqlProvider method ("
                + providerType.getName() + "." + providerMethod.getName()
                + "). Cannot invoke a method that holds "
                + (parameterTypes.length == 1 ? "named argument(@Param)": "multiple arguments")
                + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
      }
      Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
      return sqlSourceParser.parse(sql, parameterType, new HashMap<String, Object>());
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error invoking SqlProvider method ("
          + providerType.getName() + "." + providerMethod.getName()
          + ").  Cause: " + e, e);
    }
  }

  private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
    Object[] args = new Object[argumentNames.length];
    for (int i = 0; i < args.length; i++) {
      args[i] = params.get(argumentNames[i]);
    }
    return args;
  }

}
