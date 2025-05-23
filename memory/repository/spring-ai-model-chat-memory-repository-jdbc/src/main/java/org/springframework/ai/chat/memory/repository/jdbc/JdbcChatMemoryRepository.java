/*
 * Copyright 2023-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.chat.memory.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * An implementation of {@link ChatMemoryRepository} for JDBC.
 *
 * @author Jonathan Leijendekker
 * @author Thomas Vitale
 * @author Linar Abzaltdinov
 * @author Mark Pollack
 * @since 1.0.0
 */
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

	private final JdbcTemplate jdbcTemplate;

	private final JdbcChatMemoryRepositoryDialect dialect;

	private JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, JdbcChatMemoryRepositoryDialect dialect) {
		Assert.notNull(jdbcTemplate, "jdbcTemplate cannot be null");
		Assert.notNull(dialect, "dialect cannot be null");
		this.jdbcTemplate = jdbcTemplate;
		this.dialect = dialect;
	}

	@Override
	public List<String> findConversationIds() {
		List<String> conversationIds = this.jdbcTemplate.query(dialect.getSelectConversationIdsSql(), rs -> {
			var ids = new ArrayList<String>();
			while (rs.next()) {
				ids.add(rs.getString(1));
			}
			return ids;
		});
		return conversationIds != null ? conversationIds : List.of();
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		return this.jdbcTemplate.query(dialect.getSelectMessagesSql(), new MessageRowMapper(), conversationId);
	}

	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		Assert.notNull(messages, "messages cannot be null");
		Assert.noNullElements(messages, "messages cannot contain null elements");
		this.deleteByConversationId(conversationId);
		this.jdbcTemplate.batchUpdate(dialect.getInsertMessageSql(),
				new AddBatchPreparedStatement(conversationId, messages));
	}

	@Override
	public void deleteByConversationId(String conversationId) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		this.jdbcTemplate.update(dialect.getDeleteMessagesSql(), conversationId);
	}

	private record AddBatchPreparedStatement(String conversationId, List<Message> messages,
			AtomicLong instantSeq) implements BatchPreparedStatementSetter {

		private AddBatchPreparedStatement(String conversationId, List<Message> messages) {
			this(conversationId, messages, new AtomicLong(Instant.now().toEpochMilli()));
		}

		@Override
		public void setValues(PreparedStatement ps, int i) throws SQLException {
			var message = this.messages.get(i);

			ps.setString(1, this.conversationId);
			ps.setString(2, message.getText());
			ps.setString(3, message.getMessageType().name());
			ps.setTimestamp(4, new Timestamp(instantSeq.getAndIncrement()));
		}

		@Override
		public int getBatchSize() {
			return this.messages.size();
		}
	}

	private static class MessageRowMapper implements RowMapper<Message> {

		@Override
		@Nullable
		public Message mapRow(ResultSet rs, int i) throws SQLException {
			var content = rs.getString(1);
			var type = MessageType.valueOf(rs.getString(2));

			return switch (type) {
				case USER -> new UserMessage(content);
				case ASSISTANT -> new AssistantMessage(content);
				case SYSTEM -> new SystemMessage(content);
				// The content is always stored empty for ToolResponseMessages.
				// If we want to capture the actual content, we need to extend
				// AddBatchPreparedStatement to support it.
				case TOOL -> new ToolResponseMessage(List.of());
			};
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private JdbcTemplate jdbcTemplate;

		private JdbcChatMemoryRepositoryDialect dialect;

		private Builder() {
		}

		public Builder jdbcTemplate(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
			return this;
		}

		public Builder dialect(JdbcChatMemoryRepositoryDialect dialect) {
			this.dialect = dialect;
			return this;
		}

		public JdbcChatMemoryRepository build() {
			if (this.dialect == null)
				throw new IllegalStateException("Dialect must be set");
			return new JdbcChatMemoryRepository(this.jdbcTemplate, this.dialect);
		}

	}

}
