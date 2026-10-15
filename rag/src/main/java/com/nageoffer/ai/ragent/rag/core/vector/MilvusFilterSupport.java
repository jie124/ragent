/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.vector;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Milvus 共享 collection 的 {@code collection_name} 标量过滤表达式构建工具
 * <p>
 * 向量检索与全文（关键词）检索都在同一共享 collection 上按知识库过滤，故抽出公共构建逻辑，
 * 保证两路的过滤语义一致（单个库用 {@code ==}，多库用 {@code in [...]}，值做转义）
 */
public final class MilvusFilterSupport {

    private MilvusFilterSupport() {
    }

    /**
     * 构建 {@code collection_name} 标量过滤表达式；无库（空）时返回 null 表示不过滤（检索全共享库）
     *
     * @param collectionNames 目标知识库 collection 集合
     * @return 过滤表达式，空集合返回 null
     */
    public static String buildCollectionFilter(List<String> collectionNames) {
        if (collectionNames == null || collectionNames.isEmpty()) {
            return null;
        }
        if (collectionNames.size() == 1) {
            return "collection_name == \"" + escapeFilterValue(collectionNames.get(0)) + "\"";
        }
        String inList = collectionNames.stream()
                .map(MilvusFilterSupport::escapeFilterValue)
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
        return "collection_name in [" + inList + "]";
    }

    /**
     * 转义过滤表达式中的字符串字面量，防止 collection 名中的反斜杠 / 双引号破坏表达式
     */
    public static String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
