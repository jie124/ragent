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

package com.nageoffer.ai.ragent.rag.core.keyword;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusFilterSupport;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 基于 Milvus 全文检索（BM25）的关键词检索服务，{@code rag.keyword.type=milvus} 时装配
 * <p>
 * 复用向量检索的共享 collection：写入侧由 {@code MilvusVectorStoreAdmin} 建表时通过 BM25 function
 * 把 content 映射为稀疏向量，写入侧零额外维护；检索侧直接对 function 输出字段 {@code content_sparse}
 * 做全文查询，命中 {@code id} 即向量库主键 chunkId，与向量通道结果同构
 * <p>
 * 前提：{@code rag.vector.type=milvus}（依赖同一 MilvusClientV2 与 collection），且 collection 需以
 * 包含 content 分析器与 BM25 function 的 schema 创建（旧 collection 需重建）
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "milvus")
public class MilvusKeywordRetrieverService implements KeywordRetrieverService {

    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> search(String query, List<String> collectionNames, int topK) {
        String filter = MilvusFilterSupport.buildCollectionFilter(collectionNames);
        List<BaseVector> data = List.of(new EmbeddedText(query));

        var builder = SearchReq.builder()
                .collectionName(ragDefaultProperties.getCollectionName())
                .data(data)
                // 全文检索作用于 BM25 function 的输出字段，data 传文本即可，无需 ANN searchParams
                .annsField("content_sparse")
                .topK(topK)
                .outputFields(List.of("id", "content", "collection_name", "metadata"));
        if (StrUtil.isNotBlank(filter)) {
            builder.filter(filter);
        }

        SearchResp resp = milvusClient.search(builder.build());
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.get(0).stream()
                .map(r -> RetrievedChunk.builder()
                        .id(Objects.toString(r.getEntity().get("id"), ""))
                        .text(Objects.toString(r.getEntity().get("content"), ""))
                        .collectionName(Objects.toString(r.getEntity().get("collection_name"), null))
                        .score(r.getScore())
                        .build())
                .collect(Collectors.toList());
    }
}
