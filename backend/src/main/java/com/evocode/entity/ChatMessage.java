package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evocode.config.PgJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话消息（docs/07-数据字典.md §3.14，V006）。
 * citations：ASSISTANT 消息的引用列表 [{file, line, excerpt}]，jsonb。
 */
@Data
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** USER / ASSISTANT */
    private String role;

    private String content;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<Map<String, Object>> citations;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
