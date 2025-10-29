create table conversation_messages
(
    id                bigint auto_increment
        primary key,
    user_id           varchar(128)                                      not null,
    conversation_id   varchar(128)                                      not null,
    role              varchar(32)                                       null,
    content           text                                              null,
    payload           json                                              null,
    message_timestamp varchar(64)                                       null,
    created_at        timestamp               default CURRENT_TIMESTAMP not null,
    state             enum ('DRAFT', 'FINAL') default 'DRAFT'           not null,
    step_id           varchar(64)                                       null,
    seq               int                     default 0                 not null,
    constraint uq_conv_step_role_seq
        unique (user_id, conversation_id, step_id, role, seq)
);

create index idx_conv_state_created
    on conversation_messages (user_id, conversation_id, state, created_at);

create index idx_user_conv
    on conversation_messages (user_id, conversation_id);

create table tool_executions
(
    id              bigint auto_increment
        primary key,
    user_id         varchar(64)                         not null,
    conversation_id varchar(64)                         not null,
    tool_name       varchar(128)                        not null,
    args_hash       char(64)                            not null,
    status          enum ('SUCCESS', 'FAILURE')         not null,
    args_json       json                                null,
    result_json     json                                null,
    created_at      timestamp default CURRENT_TIMESTAMP not null,
    updated_at      timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    expires_at      timestamp                           null,
    constraint uk_dedup
        unique (user_id, conversation_id, tool_name, args_hash, status)
);

create index idx_tool_expire
    on tool_executions (tool_name, expires_at);

create index idx_user_conv
    on tool_executions (user_id, conversation_id);

