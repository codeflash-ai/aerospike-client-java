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
package com.aerospike.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Host name/port of database server.
 */
public final class Host {
	/**
	 * Host name or IP address of database server.
	 */
	public final String name;

	/**
	 * TLS certificate name used for secure connections.
	 */
	public final String tlsName;

	/**
	 * Port of database server.
	 */
	public final int port;

	/**
	 * Initialize host.
	 */
	public Host(String name, int port) {
		this.name = name;
		this.tlsName = null;
		this.port = port;
	}

	/**
	 * Initialize host.
	 */
	public Host(String name, String tlsName, int port) {
		this.name = name;
		this.tlsName = tlsName;
		this.port = port;
	}

	@Override
	public String toString() {
		// Ignore tlsName in string representation.
		// Use space separator to avoid confusion with IPv6 addresses that contain colons.
		return name + ' ' + port;
	}

	@Override
	public int hashCode() {
		// Ignore tlsName in default hash code.
		final int prime = 31;
		int result = prime + name.hashCode();
		return prime * result + port;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		Host other = (Host) obj;
		// Ignore tlsName in default equality comparison.
		return this.name.equals(other.name) && this.port == other.port;
	}

	/**
	 * Parse command-line hosts from string format: hostname1[:tlsname1][:port1],...
	 * <p>
	 * Hostname may also be an IP address in the following formats.
	 * <ul>
	 * <li>IPv4: xxx.xxx.xxx.xxx</li>
	 * <li>IPv6: [xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]</li>
	 * <li>IPv6: [xxxx::xxxx]</li>
	 * </ul>
	 * IPv6 addresses must be enclosed by brackets.
	 * tlsname and port are optional.
	 */
	public static Host[] parseHosts(String str, int defaultPort) {
		try {
			if (str == null) {
				throw new IllegalArgumentException("hosts string is null");
			}

			int len = str.length();
			// Quick pass to estimate number of hosts for ArrayList capacity.
			int est = 1;
			boolean inBracket = false;
			for (int i = 0; i < len; i++) {
				char c = str.charAt(i);
				if (c == '[') {
					inBracket = true;
				}
				else if (c == ']') {
					inBracket = false;
				}
				else if (c == ',' && !inBracket) {
					est++;
				}
			}

			List<Host> list = new ArrayList<>(est);
			int i = 0;
			while (i < len) {
				// Find next segment (comma not inside brackets)
				int start = i;
				inBracket = false;
				while (i < len) {
					char c = str.charAt(i);
					if (c == '[') {
						inBracket = true;
					}
					else if (c == ']') {
						inBracket = false;
					}
					else if (c == ',' && !inBracket) {
						break;
					}
					i++;
				}
				int end = i; // exclusive
				// skip comma
				if (i < len && str.charAt(i) == ',') {
					i++;
				}

				// Trim leading/trailing whitespace for the segment
				while (start < end && Character.isWhitespace(str.charAt(start))) {
					start++;
				}
				while (end > start && Character.isWhitespace(str.charAt(end - 1))) {
					end--;
				}
				if (start >= end) {
					// Empty segment is invalid.
					throw new IllegalArgumentException("Empty host segment");
				}

				String host = null;
				String tlsName = null;
				int port = defaultPort;

				int pos = start;
				// IPv6 bracketed address
				if (pos < end && str.charAt(pos) == '[') {
					pos++;
					int hostStart = pos;
					// find closing bracket
					while (pos < end && str.charAt(pos) != ']') {
						pos++;
					}
					if (pos >= end) {
						throw new IllegalArgumentException("Unterminated IPv6 address");
					}
					host = str.substring(hostStart, pos);
					pos++; // move past ']'

					// If there's more, it should be :tls or :port or :tls:port
					if (pos < end && str.charAt(pos) == ':') {
						pos++; // start of next field
						int fieldStart = pos;
						// find next colon separating tls and port (if any)
						int colonIndex = -1;
						for (int j = pos; j < end; j++) {
							if (str.charAt(j) == ':') {
								colonIndex = j;
								break;
							}
						}
						if (colonIndex == -1) {
							String field = str.substring(fieldStart, end).trim();
							if (field.length() > 0) {
								if (isDigits(field)) {
									port = Integer.parseInt(field);
								}
								else {
									tlsName = field;
								}
							}
						}
						else {
							String field1 = str.substring(fieldStart, colonIndex).trim();
							String field2 = str.substring(colonIndex + 1, end).trim();
							if (field1.length() > 0) {
								tlsName = field1;
							}
							if (field2.length() > 0) {
								port = Integer.parseInt(field2);
							}
						}
					}
				}
				else {
					// Non-bracketed: split on up to two colons
					int firstColon = -1;
					int secondColon = -1;
					for (int j = start; j < end; j++) {
						if (str.charAt(j) == ':') {
							if (firstColon == -1) {
								firstColon = j;
							}
							else {
								secondColon = j;
								break;
							}
						}
					}
					if (firstColon == -1) {
						host = str.substring(start, end).trim();
					}
					else if (secondColon == -1) {
						host = str.substring(start, firstColon).trim();
						String after = str.substring(firstColon + 1, end).trim();
						if (after.length() > 0) {
							if (isDigits(after)) {
								port = Integer.parseInt(after);
							}
							else {
								tlsName = after;
							}
						}
					}
					else {
						host = str.substring(start, firstColon).trim();
						tlsName = str.substring(firstColon + 1, secondColon).trim();
						String after = str.substring(secondColon + 1, end).trim();
						if (after.length() > 0) {
							port = Integer.parseInt(after);
						}
					}
				}

				if (host == null || host.length() == 0) {
					throw new IllegalArgumentException("Missing host");
				}

				if (tlsName == null) {
					list.add(new Host(host, port));
				}
				else {
					list.add(new Host(host, tlsName, port));
				}
			}

			return list.toArray(new Host[0]);
		}
		catch (Throwable e) {
			throw new AerospikeException("Invalid hosts string: " + str);
		}
	}

	/**
	 * Parse server service hosts from string format: hostname1:port1,...
	 * <p>
	 * Hostname may also be an IP address in the following formats.
	 * <ul>
	 * <li>IPv4: xxx.xxx.xxx.xxx</li>
	 * <li>IPv6: [xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]</li>
	 * <li>IPv6: [xxxx::xxxx]</li>
	 * </ul>
	 * IPv6 addresses must be enclosed by brackets.
	 */
	public static List<Host> parseServiceHosts(String str) {
		try {
			return new HostParser(str).parseServiceHosts();
		}
		catch (Throwable e) {
			throw new AerospikeException("Invalid service hosts string: " + str);
		}
	}

	private static class HostParser {
		private final String str;
		private int offset;
		private final int length;
		private char c;

		private HostParser(String str) {
			this.str = str;
			this.length = str.length();
			this.offset = 0;
			this.c = ',';
		}

		private Host[] parseHosts(int defaultPort) {
			ArrayList<Host> list = new ArrayList<Host>();
			String hostname;
			String tlsname;
			int port;

			while (offset < length) {
				if (c != ',') {
					throw new RuntimeException();
				}
				hostname = parseHost();
				tlsname = null;
				port = defaultPort;

				if (offset < length && c == ':') {
					String s = parseString();

					if (s.length() > 0) {
						if (Character.isDigit(s.charAt(0))) {
							// Found port.
							port = Integer.parseInt(s);
						}
						else {
							// Found tls name.
							tlsname = s;

							// Parse port.
							s = parseString();

							if (s.length() > 0) {
								port = Integer.parseInt(s);
							}
						}
					}
				}
				list.add(new Host(hostname, tlsname, port));
			}
			return list.toArray(new Host[list.size()]);
		}

		private List<Host> parseServiceHosts() {
			ArrayList<Host> list = new ArrayList<Host>();
			String hostname;
			int port;

			while (offset < length) {
				if (c != ',') {
					throw new RuntimeException();
				}
				hostname = parseHost();

				if (c != ':') {
					throw new RuntimeException();
				}

				String s = parseString();
				port = Integer.parseInt(s);

				list.add(new Host(hostname, port));
			}
			return list;
		}

		private String parseHost() {
			c = str.charAt(offset);

			if (c == '[') {
				// IPv6 addresses are enclosed by brackets.
				int begin = ++offset;

				while (offset < length) {
					c = str.charAt(offset);

					if (c == ']') {
						String s = str.substring(begin, offset++);

						if (offset < length) {
							c = str.charAt(offset++);
						}
						return s;
					}
					offset++;
				}
				throw new RuntimeException("Unterminated bracket");
			}
			else {
				return parseString();
			}
		}

		private String parseString() {
			int begin = offset;

			while (offset < length) {
				c = str.charAt(offset);

				if (c == ':' || c == ',') {
					return str.substring(begin, offset++);
				}
				offset++;
			}
			return str.substring(begin, offset);
		}
	}

    private static boolean isDigits(String s) {
    	int sl = s.length();
    	if (sl == 0) {
    		return false;
    	}
    	for (int i = 0; i < sl; i++) {
    		char c = s.charAt(i);
    		if (c < '0' || c > '9') {
    			return false;
    		}
    	}
    	return true;
    }

}
