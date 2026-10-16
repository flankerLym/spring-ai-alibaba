/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.alibaba.cloud.ai.studio.core.base.typehandler;

import com.alibaba.cloud.ai.studio.core.model.llm.domain.ParameterRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists model parameter rules as PostgreSQL JSONB while remaining compatible
 * with MySQL JSON/TEXT deployments.
 */
public class ParameterRuleListTypeHandler extends BaseTypeHandler<List<ParameterRule>> {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final TypeReference<List<ParameterRule>> TYPE = new TypeReference<>() {
	};

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, List<ParameterRule> parameter,
			JdbcType jdbcType) throws SQLException {
		String json;
		try {
			json = MAPPER.writeValueAsString(parameter);
		}
		catch (Exception e) {
			throw new SQLException("Failed to serialize model parameter rules", e);
		}

		if ("PostgreSQL".equalsIgnoreCase(ps.getConnection().getMetaData().getDatabaseProductName())) {
			ps.setObject(i, json, Types.OTHER);
		}
		else {
			ps.setString(i, json);
		}
	}

	@Override
	public List<ParameterRule> getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return parse(rs.getString(columnName));
	}

	@Override
	public List<ParameterRule> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return parse(rs.getString(columnIndex));
	}

	@Override
	public List<ParameterRule> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return parse(cs.getString(columnIndex));
	}

	private List<ParameterRule> parse(String value) throws SQLException {
		if (value == null || value.isBlank()) {
			return new ArrayList<>();
		}
		try {
			return MAPPER.readValue(value, TYPE);
		}
		catch (Exception e) {
			throw new SQLException("Failed to deserialize model parameter rules", e);
		}
	}

}
