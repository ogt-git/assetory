package com.project.assetory.analysis.dart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.assetory.analysis.exception.DartApiException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 고유번호(corpCode.xml) 응답 → stock_code → corp_code. 상장사(stock_code 있음)만 담는다 — 비상장은 공백 1칸으로 온다.
 * - ZIP(PK)이 아니면 오류 응답(JSON 또는 XML)으로 보고 status로 분류
 * - 해석 불가 · 상장사 0건 → INVALID_RESPONSE (빈 맵이면 모든 종목이 "공시 없음"으로 굳는다)
 * - JDK ZipInputStream + StAX, DTD · 외부 엔티티 비활성(XXE 방지). 새 라이브러리 없음
 */
public final class DartCorpCodeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern XML_STATUS = Pattern.compile("<status>\\s*(\\d{3})\\s*</status>");

    private DartCorpCodeParser() {
    }

    public static Map<String, String> parse(byte[] body) {
        if (body == null || body.length < 2) {
            throw invalid();
        }
        if (body[0] != 'P' || body[1] != 'K') {
            throw errorFrom(body);
        }
        Map<String, String> codes = unzip(body);
        if (codes.isEmpty()) {
            throw invalid();
        }
        return Map.copyOf(codes);
    }

    private static Map<String, String> unzip(byte[] body) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().toLowerCase().endsWith(".xml")) {
                    return readXml(zip);
                }
            }
            throw invalid();
        } catch (IOException | XMLStreamException e) {
            throw invalid();
        }
    }

    private static Map<String, String> readXml(ZipInputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(in, "UTF-8");
        Map<String, String> codes = new HashMap<>();
        String corpCode = null;
        StringBuilder stockCode = null;
        String current = null;
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                current = reader.getLocalName();
                text.setLength(0);
                if ("list".equals(current)) {
                    corpCode = null;
                    stockCode = null;
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = reader.getLocalName();
                if ("corp_code".equals(name)) corpCode = text.toString().strip();
                if ("stock_code".equals(name)) stockCode = new StringBuilder(text.toString().strip());
                if ("list".equals(name) && corpCode != null && !corpCode.isEmpty()
                        && stockCode != null && !stockCode.isEmpty()) {
                    codes.put(stockCode.toString(), corpCode);
                }
                text.setLength(0);
                current = null;
            }
        }
        return codes;
    }

    private static DartApiException errorFrom(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        String status = null;
        if (text.startsWith("{")) {
            try {
                JsonNode node = MAPPER.readTree(text).path("status");
                status = node.isMissingNode() || node.isNull() ? null : node.asText();
            } catch (IOException e) {
                status = null;
            }
        } else {
            Matcher m = XML_STATUS.matcher(text);
            if (m.find()) status = m.group(1);
        }
        if (status == null || DartListParser.STATUS_OK.equals(status)) {
            return invalid();
        }
        return new DartApiException(DartErrorType.fromStatus(status), status);
    }

    private static DartApiException invalid() {
        return new DartApiException(DartErrorType.INVALID_RESPONSE, null);
    }
}
