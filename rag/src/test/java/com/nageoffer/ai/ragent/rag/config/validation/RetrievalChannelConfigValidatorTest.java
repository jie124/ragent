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

package com.nageoffer.ai.ragent.rag.config.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalChannelConfigValidatorTest {

    private static List<RetrievalChannelConfigValidator.Violation> validate(Map<String, String> types,
                                                                            Map<String, Boolean> enabled) {
        Function<String, String> typeReader = types::get;
        Predicate<String> enabledReader = key -> enabled.getOrDefault(key, false);
        return RetrievalChannelConfigValidator.validate(typeReader, enabledReader);
    }

    @Test
    @DisplayName("关键词后端为 es 且通道启用时不报错")
    void esBackendWithEnabledChannelIsValid() {
        List<RetrievalChannelConfigValidator.Violation> violations = validate(
                Map.of("rag.keyword.type", "es"),
                Map.of("rag.search.channels.keyword.enabled", true));

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("关键词后端为 milvus 且通道启用时不报错")
    void milvusBackendWithEnabledChannelIsValid() {
        List<RetrievalChannelConfigValidator.Violation> violations = validate(
                Map.of("rag.keyword.type", "milvus"),
                Map.of("rag.search.channels.keyword.enabled", true));

        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("关键词后端为 none 却启用通道时报错，并提示 es / milvus")
    void noneBackendWithEnabledChannelReportsViolation() {
        List<RetrievalChannelConfigValidator.Violation> violations = validate(
                Map.of("rag.keyword.type", "none"),
                Map.of("rag.search.channels.keyword.enabled", true));

        assertEquals(1, violations.size());
        assertEquals("关键词检索", violations.get(0).channelLabel());
        assertEquals("es / milvus", violations.get(0).requiredType());
    }

    @Test
    @DisplayName("关键词后端装配但通道关闭时不报错")
    void milvusBackendWithChannelOffIsValid() {
        List<RetrievalChannelConfigValidator.Violation> violations = validate(
                Map.of("rag.keyword.type", "milvus"),
                Map.of("rag.search.channels.keyword.enabled", false));

        assertTrue(violations.isEmpty());
    }
}
