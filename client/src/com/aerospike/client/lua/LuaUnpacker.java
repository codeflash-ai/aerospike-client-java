/*
 * Copyright 2012-2023 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.lua;

import java.util.List;
import java.util.Map;

import org.luaj.vm2.LuaBoolean;
import org.luaj.vm2.LuaDouble;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaValue;

import com.aerospike.client.util.Unpacker;

public class LuaUnpacker extends Unpacker<LuaValue> {
	private LuaInstance instance;
    private static final int STRING_CACHE_SIZE = 256;
    private static final int STRING_CACHE_MASK = STRING_CACHE_SIZE - 1;
    private final String[] stringCacheKeys = new String[STRING_CACHE_SIZE];
    private final LuaString[] stringCacheValues = new LuaString[STRING_CACHE_SIZE];

	public LuaUnpacker(LuaInstance instance, byte[] buffer, int offset, int length) {
		super(buffer, offset, length);
		this.instance = instance;
	}

	@Override
	protected LuaMap getMap(Map<LuaValue,LuaValue> value) {
		return new LuaMap(instance, value);
	}

	@Override
	protected LuaList getList(List<LuaValue> value) {
		return new LuaList(instance, value);
	}

	@Override
	protected LuaBytes getBlob(byte[] value) {
		return new LuaBytes(instance, value);
	}

	@Override
	protected LuaString getString(String value) {
		// Preserve original behavior for null values (delegate directly),
		// so any exceptions or handling that LuaString.valueOf does remain unchanged.
		if (value == null) {
			return LuaString.valueOf(value);
		}

		int h = value.hashCode();
		int idx = (h ^ (h >>> 16)) & STRING_CACHE_MASK;
		String key = stringCacheKeys[idx];

		// Fast positive-hit path: return cached LuaString when key matches.
		if (key != null && key.equals(value)) {
			return stringCacheValues[idx];
		}

		// Miss: create LuaString and populate the cache slot.
		LuaString ls = LuaString.valueOf(value);
		stringCacheKeys[idx] = value;
		stringCacheValues[idx] = ls;
		return ls;
	}

	@Override
	protected LuaNumber getLong(long value) {
		return LuaInteger.valueOf(value);
	}

	@Override
	protected LuaNumber getDouble(double value) {
		return LuaDouble.valueOf(value);
	}

	@Override
	protected LuaBoolean getBoolean(boolean value) {
		return LuaBoolean.valueOf(value);
	}

	@Override
	protected LuaGeoJSON getGeoJSON(String value) {
		return new LuaGeoJSON(value);
	}
}
