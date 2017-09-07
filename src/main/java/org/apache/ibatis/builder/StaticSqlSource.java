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
package org.apache.ibatis.builder;

import java.util.List;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * 静态的SqlSource， 对于SQL和参数映射的一个简单封装
 * 主要包含了sql和参数，但只有这两项还不行，还需要有实际参数
 * @author Clinton Begin
 */
public class StaticSqlSource implements SqlSource {


    private final String sql; //一个可以直接被执行的SQL
    private final List<ParameterMapping> parameterMappings; //SQL中对应的参数列表
    private final Configuration configuration; //配置

    public StaticSqlSource(Configuration configuration, String sql) {
        this(configuration, sql, null);
    }

    public StaticSqlSource(Configuration configuration, String sql, List<ParameterMapping> parameterMappings) {
        this.sql = sql;
        this.parameterMappings = parameterMappings;
        this.configuration = configuration;
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        //返回一个BoundSql，boundSql中带有实际参数，同时也封装了SqlSource中的三个属性
        return new BoundSql(configuration, sql, parameterMappings, parameterObject);
    }

}
