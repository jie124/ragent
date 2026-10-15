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

package com.nageoffer.ai.ragent.agent.state;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentStateMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AgentStateStore 的 PostgreSQL 实现。
 * <p>
 * 它是 Ragent 交给 AgentScope 框架的「状态存取通道」——真正拼给大模型的 messages（历史消息列表）
 * 就存/取在这里（落在 {@code t_agent_state} 表），而不是 {@code t_agent_message} 那张流水账表。
 * <p>
 * 调用方是框架内部的 ReActAgent：run 开始前 {@code get} 恢复历史，推理结束/run 结束 {@code save}
 * 写回历史；Ragent 不直接调这些方法，只在 {@code bindReleaseHooks} 里借 {@code agent.saveAgentState}
 * 在异常/取消时补一次存盘。
 * <p>
 * 一个 (userId, sessionId, stateKey) 三元组定位一条状态；stateKey 区分状态类型，主键位是会话消息历史，
 * 其余类型（如计划、列表）共用同一张表不同 key。
 */

/**
 * get 和  save的调用时机
 *
 * ● 直接讲 get 和 save 的调用时机。先记住一个大前提：这两个方法不是 Ragent 自己的代码调的，是 AgentScope 框架（ReActAgent）在内部调的。Ragent 里能看到的只是 ReActAgent
 *   的几个门面方法（streamEvents / getAgentState / saveAgentState / clearStateCache），真正落到 PgAgentStateStore.get/save 的那一步发生在框架 jar 内部。
 *
 *   get —— 什么时候调
 *
 *   框架调它的时机：run 开始、恢复历史时。
 *
 *   具体链路：
 *
 *   1. AgentChatServiceImpl.launchStream 第 283 行调 agent.streamEvents(input, runtimeContext)，这是点火动作。
 *   2. ReActAgent 收到后，跑 ReAct 循环之前要先知道「这个会话上一轮聊到哪了」，于是用 (userId, sessionId, 消息历史key) 去 get，把上一轮存下来的 messages 读出来。
 *   3. 读出来的历史 + 人设 + 当前输入，拼成发给大模型的 messages。
 *
 *   两个关键细节：
 *
 *   - 不是每次都打 DB。ReActAgent 有内存缓存，getAgentState(userId, conversationId)（第 218 行就是用它）优先走缓存；只有缓存里没有这个 session
 *     的状态、第一次恢复历史时，才真正命中 PgAgentStateStore.get。这也解释了为什么代码里有那么多 clearStateCache 的调用（第 220、294、316 行）——清了缓存，下次才会重新 get
 *     落库数据。
 *   - 首问 vs 续问：新会话第一次 get 返回 Optional.empty()（查不到），框架就知道「没历史」，从零开始；续会话 get 返回上一轮存的历史。
 *
 *   save —— 什么时候调
 *
 *   分两条路径：
 *
 *   ① 框架正常路径（主要）：
 *
 *   ReActAgent 每完成一轮推理、以及整个 run 结束时，把最新的消息状态 save 回 store。对应注释第 54 行那句话：写回是为了「下一轮 / 下一次会话续上历史」。这是常态。
 *
 *   ② Ragent 补存路径（兜底）：
 *
 *   bindReleaseHooks（第 303 行）注册的收尾钩子：
 *
 *   runHandle.onRelease(() -> {
 *       if (runHandle.isStateSaveRequired()) {        // 出错 / 取消 / 流异常结束
 *           agent.saveAgentState(userId, conversationId);   // 第 311 行，显式补存
 *       }
 *       agent.clearStateCache(userId, conversationId);      // 第 316 行
 *   });
 *
 *   注释写得很明白（第 308 行）：「正常完成仍由框架保存，不重复补存」。也就是说：
 *
 *   - 正常跑完 → 框架自己 save 过了，isStateSaveRequired() 为 false，Ragent 不重复存；
 *   - 出错/取消/SSE 断开导致流异常结束 → 框架可能没来得及存，isStateSaveRequired() 为 true，Ragent 补一次 saveAgentState，最终也走到 PgAgentStateStore.save。
 *
 *   一句话总结
 *
 *   ┌────────────┬─────────────────────────┬───────────────────────────────────────────────────────────────────────┐
 *   │    方法    │          谁调           │                               什么时候                                │
 *   ├────────────┼─────────────────────────┼───────────────────────────────────────────────────────────────────────┤
 *   │ get        │ 框架 ReActAgent         │ 每次 streamEvents 点火后、跑 ReAct 循环前，恢复历史（首问返回 empty） │
 *   ├────────────┼─────────────────────────┼───────────────────────────────────────────────────────────────────────┤
 *   │ save       │ 框架 ReActAgent         │ 每轮推理结束 / run 结束，写回最新消息历史                             │
 *   ├────────────┼─────────────────────────┼───────────────────────────────────────────────────────────────────────┤
 *   │ save（补） │ Ragent bindReleaseHooks │ 出错/取消时，框架没存上，Ragent 兜底补存                              │
 *   └────────────┴─────────────────────────┴───────────────────────────────────────────────────────────────────────┘
 *
 *   另外注意区分两个 save 重载：单对象 save(State) 存的是消息历史（主 key）；列表 save(List) 存的是计划、条目列表这类「一批状态」，两者共用同一张表、同一个
 *   upsert，只是序列化形态不同。
 *
 *
 *
 *
 *
 */



@RequiredArgsConstructor
public class PgAgentStateStore implements AgentStateStore {

    private final AgentStateMapper agentStateMapper;

    /**
     * 存单个状态对象（整段序列化成 JSON 后落表，同 key 覆盖）。
     * <p>
     * 框架调用时机：ReActAgent 每完成一轮推理、或整个 run 结束时，把最新的消息状态写回，
     * 供下一轮 / 下一次会话续上历史。Ragent 的收尾补存（异常/取消）最终也走到这里。
     */
    @Override
    public void save(String userId, String sessionId, String key, State value) {
        agentStateMapper.upsert(userId, sessionId, key, JsonUtils.getJsonCodec().toJson(value));
    }

    /**
     * 存列表型状态（整段序列化，与单对象共用同一张表、同一个 upsert 通道）。
     * <p>
     * 框架调用时机：需要持久化「一批状态」而非单个对象时走这里，例如多步计划、某类条目列表。
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        agentStateMapper.upsert(userId, sessionId, key, JsonUtils.getJsonCodec().toJson(values));
    }

    /**
     * 读单个状态：查到 JSON 后按 {@code type} 反序列化，查不到返回 empty（不是抛异常）。
     * <p>
     * 框架调用时机：每次 {@code streamEvents} 点火后，ReActAgent 用 sessionId 从这恢复上一轮存下的
     * 消息历史，再拼上人设和当前输入，组成发给大模型的 messages。这是「历史怎么进上下文」的真正入口。
     */
    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        String payload = queryPayload(userId, sessionId, key);
        if (StrUtil.isBlank(payload)) {
            return Optional.empty();
        }
        return Optional.ofNullable(JsonUtils.getJsonCodec().fromJson(payload, type));
    }

    /**
     * 读列表型状态：先按 JSON 数组解一层，再逐元素转成 {@code itemType}。
     * <p>
     * 框架调用时机：与 {@link #get} 同理，只是状态本身是列表形态；空表返回空列表。
     */
    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
        String payload = queryPayload(userId, sessionId, key);
        if (StrUtil.isBlank(payload)) {
            return List.of();
        }
        List<?> rawItems = JsonUtils.getJsonCodec().fromJson(payload, List.class);
        List<T> result = new ArrayList<>(rawItems.size());
        for (Object item : rawItems) {
            result.add(JsonUtils.getJsonCodec().convertValue(item, itemType));
        }
        return result;
    }

    /**
     * 判断该 session 是否已有任何状态（只要 (userId, sessionId) 下存在任意 key 即 true）。
     * <p>
     * 框架/业务调用时机：判断会话是否已存在、是否需要初始化；Ragent 侧也用它区分「新会话」与「续会话」。
     */
    @Override
    public boolean exists(String userId, String sessionId) {
        return agentStateMapper.exists(userId, sessionId);
    }

    /**
     * 清空整个 session 的所有状态（不区分 key）。
     * <p>
     * 调用时机：删除会话 / 清空会话时，把该会话的工作状态整段抹掉，下一次请求相当于全新会话。
     */
    @Override
    public void delete(String userId, String sessionId) {
        agentStateMapper.deleteBySession(userId, sessionId);
    }

    /**
     * 删除指定 key 的一条状态（保留该 session 下其它 key）。
     * <p>
     * 调用时机：只清某类状态（如单独清计划、单独清记忆）时用，比整段 delete 更精细。
     */
    @Override
    public void delete(String userId, String sessionId, String key) {
        agentStateMapper.deleteByKey(userId, sessionId, key);
    }

    /**
     * 列出该用户所有 sessionId（去重、按字典序，与 DB 里的 order by 一致）。
     * <p>
     * 调用时机：枚举某个用户的历史会话，用于会话列表 / 批量清理 / 统计等场景。
     */
    @Override
    public Set<String> listSessionIds(String userId) {
        return new LinkedHashSet<>(agentStateMapper.selectSessionIds(userId));
    }

    /**
     * 读 payload 的统一出口：所有 get 类方法先查 JSON 原文，查不到返回 null。
     */
    private String queryPayload(String userId, String sessionId, String key) {
        return agentStateMapper.selectPayload(userId, sessionId, key);
    }
}
