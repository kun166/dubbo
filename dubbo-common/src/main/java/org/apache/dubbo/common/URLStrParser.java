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
package org.apache.dubbo.common;

import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.url.component.URLItemCache;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY_PREFIX;
import static org.apache.dubbo.common.utils.StringUtils.EMPTY_STRING;
import static org.apache.dubbo.common.utils.StringUtils.decodeHexByte;
import static org.apache.dubbo.common.utils.Utf8Utils.decodeUtf8;

public final class URLStrParser {
    public static final String ENCODED_QUESTION_MARK = "%3F";
    public static final String ENCODED_TIMESTAMP_KEY = "timestamp%3D";
    public static final String ENCODED_PID_KEY = "pid%3D";
    public static final String ENCODED_AND_MARK = "%26";
    private static final char SPACE = 0x20;

    private static final ThreadLocal<TempBuf> DECODE_TEMP_BUF = ThreadLocal.withInitial(() -> new TempBuf(1024));

    private URLStrParser() {
        // empty
    }

    /**
     * <p>
     * {@link URL#valueOf(java.lang.String, boolean)}中调用
     * </p>
     * 返回一个{@link ServiceConfigURL}
     *
     * @param decodedURLStr : after {@link URL#decode} string
     *                      decodedURLStr format: protocol://username:password@host:port/path?k1=v1&k2=v2
     *                      [protocol://][username:password@][host:port]/[path][?k1=v1&k2=v2]
     */
    public static URL parseDecodedStr(String decodedURLStr) {
        Map<String, String> parameters = null;
        int pathEndIdx = decodedURLStr.indexOf('?');
        if (pathEndIdx >= 0) {
            parameters = parseDecodedParams(decodedURLStr, pathEndIdx + 1);
        } else {
            pathEndIdx = decodedURLStr.length();
        }

        String decodedBody = decodedURLStr.substring(0, pathEndIdx);
        return parseURLBody(decodedURLStr, decodedBody, parameters);
    }

    /**
     * 解析url中的参数
     * <p>
     * {@link URLStrParser#parseDecodedStr(java.lang.String)}中调用
     * </p>
     *
     * @param str  url字符串
     * @param from url中?的位置
     * @return
     */
    private static Map<String, String> parseDecodedParams(String str, int from) {
        int len = str.length();
        if (from >= len) {
            // 说明没有参数
            return Collections.emptyMap();
        }

        TempBuf tempBuf = DECODE_TEMP_BUF.get();
        // 返回对象
        Map<String, String> params = new HashMap<>();
        // 记录参数开始的地方
        int nameStart = from;
        // 记录value开始的地方
        int valueStart = -1;
        int i;
        for (i = from; i < len; i++) {
            char ch = str.charAt(i);
            switch (ch) {
                case '=':
                    /**
                     * 找到=,则两边分别是key和value
                     */
                    if (nameStart == i) {
                        nameStart = i + 1;
                    } else if (valueStart < nameStart) {
                        valueStart = i + 1;
                    }
                    break;
                case ';':
                case '&':
                    /**
                     * 碰到;或者&，说明一个新的key和value
                     */
                    addParam(str, false, nameStart, valueStart, i, params, tempBuf);
                    nameStart = i + 1;
                    break;
                default:
                    // continue
            }
        }
        addParam(str, false, nameStart, valueStart, i, params, tempBuf);
        return params;
    }

    /**
     * <p>
     * {@link URLStrParser#parseDecodedStr(java.lang.String)}中调用
     * </p>
     * <dubbo:registry id="registry1" address="zookeeper://127.0.0.1:2181?registry-type=service"/>
     *
     * @param fullURLStr  : fullURLString,url全部字符串
     * @param decodedBody : format: [protocol://][username:password@][host:port]/[path] url参数之前的部分
     * @param parameters  : 记录参数的map
     * @return URL {@link ServiceConfigURL}
     */
    private static URL parseURLBody(String fullURLStr, String decodedBody, Map<String, String> parameters) {
        int starIdx = 0, endIdx = decodedBody.length();
        // ignore the url content following '#'
        int poundIndex = decodedBody.indexOf('#');
        if (poundIndex != -1) {
            endIdx = poundIndex;
        }

        // 协议，如zookeeper
        String protocol = null;
        int protoEndIdx = decodedBody.indexOf("://");
        if (protoEndIdx >= 0) {
            if (protoEndIdx == 0) {
                throw new IllegalStateException("url missing protocol: \"" + fullURLStr + "\"");
            }
            protocol = decodedBody.substring(0, protoEndIdx);
            // 将指针指向 "://" 之后
            starIdx = protoEndIdx + 3;
        } else {
            // case: file:/path/to/file.txt
            protoEndIdx = decodedBody.indexOf(":/");
            if (protoEndIdx >= 0) {
                if (protoEndIdx == 0) {
                    throw new IllegalStateException("url missing protocol: \"" + fullURLStr + "\"");
                }
                protocol = decodedBody.substring(0, protoEndIdx);
                starIdx = protoEndIdx + 1;
            }
        }

        String path = null;
        int pathStartIdx = indexOf(decodedBody, '/', starIdx, endIdx);
        if (pathStartIdx >= 0) {
            /**
             * 如:zookeeper://127.0.0.1:2181?registry-type=service
             * 则pathStartIdx=-1
             * 如:format: protocol://username:password@host:port/path?k1=v1&k2=v2
             * 则有值
             */
            path = decodedBody.substring(pathStartIdx + 1, endIdx);
            endIdx = pathStartIdx;
        }

        // 解析username和password
        String username = null;
        String password = null;
        int pwdEndIdx = lastIndexOf(decodedBody, '@', starIdx, endIdx);
        if (pwdEndIdx > 0) {
            int passwordStartIdx = indexOf(decodedBody, ':', starIdx, pwdEndIdx);
            if (passwordStartIdx != -1) { // tolerate incomplete user pwd input, like '1234@'
                username = decodedBody.substring(starIdx, passwordStartIdx);
                password = decodedBody.substring(passwordStartIdx + 1, pwdEndIdx);
            } else {
                username = decodedBody.substring(starIdx, pwdEndIdx);
            }
            starIdx = pwdEndIdx + 1;
        }

        // 解析host和port
        String host = null;
        int port = 0;
        int hostEndIdx = lastIndexOf(decodedBody, ':', starIdx, endIdx);
        if (hostEndIdx > 0 && hostEndIdx < decodedBody.length() - 1) {
            if (lastIndexOf(decodedBody, '%', starIdx, endIdx) > hostEndIdx) {
                // ipv6 address with scope id
                // e.g. fe80:0:0:0:894:aeec:f37d:23e1%en0
                // see https://howdoesinternetwork.com/2013/ipv6-zone-id
                // ignore
            } else {
                port = Integer.parseInt(decodedBody.substring(hostEndIdx + 1, endIdx));
                endIdx = hostEndIdx;
            }
        }

        if (endIdx > starIdx) {
            host = decodedBody.substring(starIdx, endIdx);
        }

        // check cache
        // 解析protocol
        protocol = URLItemCache.intern(protocol);
        path = URLItemCache.checkPath(path);

        return new ServiceConfigURL(protocol, username, password, host, port, path, parameters);
    }

    public static String[] parseRawURLToArrays(String rawURLStr, int pathEndIdx) {
        String[] parts = new String[2];
        int paramStartIdx = pathEndIdx + 3; // skip ENCODED_QUESTION_MARK
        if (pathEndIdx == -1) {
            pathEndIdx = rawURLStr.indexOf('?');
            if (pathEndIdx == -1) {
                // url with no params, decode anyway
                rawURLStr = URL.decode(rawURLStr);
            } else {
                paramStartIdx = pathEndIdx + 1;
            }
        }
        if (pathEndIdx >= 0) {
            parts[0] = rawURLStr.substring(0, pathEndIdx);
            parts[1] = rawURLStr.substring(paramStartIdx);
        } else {
            parts = new String[]{rawURLStr};
        }
        return parts;
    }

    public static Map<String, String> parseParams(String rawParams, boolean encoded) {
        if (encoded) {
            return parseEncodedParams(rawParams, 0);
        }
        return parseDecodedParams(rawParams, 0);
    }

    /**
     * @param encodedURLStr : after {@link URL#encode(String)} string
     *                      encodedURLStr after decode format: protocol://username:password@host:port/path?k1=v1&k2=v2
     *                      [protocol://][username:password@][host:port]/[path][?k1=v1&k2=v2]
     */
    public static URL parseEncodedStr(String encodedURLStr) {
        Map<String, String> parameters = null;
        int pathEndIdx = encodedURLStr.toUpperCase().indexOf("%3F"); // '?'
        if (pathEndIdx >= 0) {
            parameters = parseEncodedParams(encodedURLStr, pathEndIdx + 3);
        } else {
            pathEndIdx = encodedURLStr.length();
        }

        // decodedBody format: [protocol://][username:password@][host:port]/[path]
        String decodedBody = decodeComponent(encodedURLStr, 0, pathEndIdx, false, DECODE_TEMP_BUF.get());
        return parseURLBody(encodedURLStr, decodedBody, parameters);
    }

    private static Map<String, String> parseEncodedParams(String str, int from) {
        int len = str.length();
        if (from >= len) {
            return Collections.emptyMap();
        }

        TempBuf tempBuf = DECODE_TEMP_BUF.get();
        Map<String, String> params = new HashMap<>();
        int nameStart = from;
        int valueStart = -1;
        int i;
        for (i = from; i < len; i++) {
            char ch = str.charAt(i);
            if (ch == '%') {
                if (i + 3 > len) {
                    throw new IllegalArgumentException("unterminated escape sequence at index " + i + " of: " + str);
                }
                ch = (char) decodeHexByte(str, i + 1);
                i += 2;
            }

            switch (ch) {
                case '=':
                    if (nameStart == i) {
                        nameStart = i + 1;
                    } else if (valueStart < nameStart) {
                        valueStart = i + 1;
                    }
                    break;
                case ';':
                case '&':
                    addParam(str, true, nameStart, valueStart, i - 2, params, tempBuf);
                    nameStart = i + 1;
                    break;
                default:
                    // continue
            }
        }
        addParam(str, true, nameStart, valueStart, i, params, tempBuf);
        return params;
    }

    /**
     * 解析url中，传入的参数位置的key和value,放到参数params中
     * <p>
     * {@link URLStrParser#parseDecodedParams(java.lang.String, int)}中调用
     * </p>
     *
     * @param str        url字符串
     * @param isEncoded  是否编码或者解码
     * @param nameStart  url的参数开始坐标
     * @param valueStart url的value开始坐标
     * @param valueEnd   url的value结束坐标
     * @param params     保存参数的map
     * @param tempBuf
     * @return
     */
    private static boolean addParam(String str,
                                    boolean isEncoded,
                                    int nameStart,
                                    int valueStart,
                                    int valueEnd,
                                    Map<String, String> params,
                                    TempBuf tempBuf) {
        if (nameStart >= valueEnd) {
            /**
             * 如果value的结束位置小于等于key的起始位置,返回
             */
            return false;
        }

        if (valueStart <= nameStart) {
            /**
             * 如果value的起始位置小于name的其实位置，因为中间还有一个=,因此+1开始
             */
            valueStart = valueEnd + 1;
        }

        if (isEncoded) {
            String name = decodeComponent(str, nameStart, valueStart - 3, false, tempBuf);
            String value;
            if (valueStart >= valueEnd) {
                value = "";
            } else {
                value = decodeComponent(str, valueStart, valueEnd, false, tempBuf);
            }
            URLItemCache.putParams(params, name, value);
            // compatible with lower versions registering "default." keys
            if (name.startsWith(DEFAULT_KEY_PREFIX)) {
                params.putIfAbsent(name.substring(DEFAULT_KEY_PREFIX.length()), value);
            }
        } else {
            // 不需要编解码
            String name = str.substring(nameStart, valueStart - 1);
            String value;
            if (valueStart >= valueEnd) {
                value = "";
            } else {
                value = str.substring(valueStart, valueEnd);
            }
            URLItemCache.putParams(params, name, value);
            // compatible with lower versions registering "default." keys
            if (name.startsWith(DEFAULT_KEY_PREFIX)) {
                // 如果key中以"default."起始，则截取后面的字符串为key，放到参数列表里
                params.putIfAbsent(name.substring(DEFAULT_KEY_PREFIX.length()), value);
            }
        }
        return true;
    }

    private static String decodeComponent(String s, int from, int toExcluded, boolean isPath, TempBuf tempBuf) {
        int len = toExcluded - from;
        if (len <= 0) {
            return EMPTY_STRING;
        }

        int firstEscaped = -1;
        for (int i = from; i < toExcluded; i++) {
            char c = s.charAt(i);
            if (c == '%' || c == '+' && !isPath) {
                firstEscaped = i;
                break;
            }
        }
        if (firstEscaped == -1) {
            return s.substring(from, toExcluded);
        }

        // Each encoded byte takes 3 characters (e.g. "%20")
        int decodedCapacity = (toExcluded - firstEscaped) / 3;
        byte[] buf = tempBuf.byteBuf(decodedCapacity);
        char[] charBuf = tempBuf.charBuf(len);
        s.getChars(from, firstEscaped, charBuf, 0);

        int charBufIdx = firstEscaped - from;
        return decodeUtf8Component(s, firstEscaped, toExcluded, isPath, buf, charBuf, charBufIdx);
    }

    private static String decodeUtf8Component(
        String str, int firstEscaped, int toExcluded, boolean isPath, byte[] buf, char[] charBuf, int charBufIdx) {
        int bufIdx;
        for (int i = firstEscaped; i < toExcluded; i++) {
            char c = str.charAt(i);
            if (c != '%') {
                charBuf[charBufIdx++] = c != '+' || isPath ? c : SPACE;
                continue;
            }

            bufIdx = 0;
            do {
                if (i + 3 > toExcluded) {
                    throw new IllegalArgumentException("unterminated escape sequence at index " + i + " of: " + str);
                }
                buf[bufIdx++] = decodeHexByte(str, i + 1);
                i += 3;
            } while (i < toExcluded && str.charAt(i) == '%');
            i--;

            charBufIdx += decodeUtf8(buf, 0, bufIdx, charBuf, charBufIdx);
        }
        return new String(charBuf, 0, charBufIdx);
    }

    private static int indexOf(String str, char ch, int from, int toExclude) {
        from = Math.max(from, 0);
        toExclude = Math.min(toExclude, str.length());
        if (from > toExclude) {
            return -1;
        }

        for (int i = from; i < toExclude; i++) {
            if (str.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(String str, char ch, int from, int toExclude) {
        from = Math.max(from, 0);
        toExclude = Math.min(toExclude, str.length() - 1);
        if (from > toExclude) {
            return -1;
        }

        for (int i = toExclude; i >= from; i--) {
            if (str.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }

    private static final class TempBuf {

        private final char[] chars;

        private final byte[] bytes;

        TempBuf(int bufSize) {
            this.chars = new char[bufSize];
            this.bytes = new byte[bufSize];
        }

        public char[] charBuf(int size) {
            char[] chars = this.chars;
            if (size <= chars.length) {
                return chars;
            }
            return new char[size];
        }

        public byte[] byteBuf(int size) {
            byte[] bytes = this.bytes;
            if (size <= bytes.length) {
                return bytes;
            }
            return new byte[size];
        }
    }
}
