package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户。
 * <p>
 * 字段与列名的映射靠 `map-underscore-to-camel-case` 自动完成（password_hash → passwordHash）。
 * {@code deleted} 标了 {@link TableLogic}，从此所有 BaseMapper 的查询都会自动带 `deleted = 0`，
 * 删除也会自动变成 UPDATE —— 不需要在每个 SQL 里手写。
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    /** BCrypt 哈希。任何时候都不要把明文密码放进这个字段。 */
    private String passwordHash;

    private String nickname;

    /** USER / ADMIN */
    private String role;

    /** 1 正常 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
