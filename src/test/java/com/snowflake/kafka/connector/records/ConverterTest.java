/*
 * Copyright (c) 2019 Snowflake Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.snowflake.kafka.connector.records;

import com.snowflake.kafka.connector.internal.SnowflakeKafkaConnectorException;
import com.snowflake.kafka.connector.mock.MockSchemaRegistryClient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;

import io.confluent.connect.avro.AvroConverter;
import io.confluent.connect.avro.AvroData;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import net.snowflake.client.jdbc.internal.apache.commons.codec.Charsets;
import net.snowflake.client.jdbc.internal.apache.commons.codec.binary.Hex;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.core.JsonProcessingException;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.LogicalTypes;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.SchemaAndValue;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.connect.json.JsonConverter;
import org.junit.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static com.snowflake.kafka.connector.records.RecordService.MAX_SNOWFLAKE_NUMBER_PRECISION;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ConverterTest
{

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final String TEST_FILE_NAME = "test.avro";

  private static final String TEST_TOPIC = "test";

  @Test
  public void testJsonConverter()
  {
    SnowflakeConverter converter = new SnowflakeJsonConverter();

    ObjectNode node = mapper.createObjectNode();

    node.put("str", "test");
    node.put("num", 123);

    SchemaAndValue sv =
      converter.toConnectData("test", node.toString().getBytes(StandardCharsets.UTF_8));

    assert sv.schema().name().equals(SnowflakeJsonSchema.NAME);

    assert sv.value() instanceof SnowflakeRecordContent;

    SnowflakeRecordContent content = (SnowflakeRecordContent) sv.value();

    JsonNode[] jsonNodes = content.getData();

    assert jsonNodes.length == 1;
    assert node.toString().equals(jsonNodes[0].toString());

    //null value
    sv = converter.toConnectData("test",null);
    assert ((SnowflakeRecordContent) sv.value()).getData()[0].toString().equals("{}");
  }

  @Test
  public void testAvroConverter() throws IOException
  {
    //todo: test schema registry
    URL resource = ConverterTest.class.getResource(TEST_FILE_NAME);

    byte[] testFile = Files.readAllBytes(Paths.get(resource.getFile()));

    SnowflakeConverter converter =
      new SnowflakeAvroConverterWithoutSchemaRegistry();

    SchemaAndValue sv = converter.toConnectData("test", testFile);

    assert sv.schema().name().equals(SnowflakeJsonSchema.NAME);

    assert sv.value() instanceof SnowflakeRecordContent;

    SnowflakeRecordContent content = (SnowflakeRecordContent) sv.value();

    JsonNode[] jsonNodes = content.getData();

    assert jsonNodes.length == 2;

    assert jsonNodes[0].toString().equals("{\"name\":\"foo\",\"age\":30}");
    assert jsonNodes[1].toString().equals("{\"name\":\"bar\",\"age\":29}");

    //null value
    sv = converter.toConnectData("test",null);
    assert ((SnowflakeRecordContent) sv.value()).getData()[0].toString().equals("{}");
  }

  @Test
  public void testAvroWithSchemaRegistry() throws IOException
  {
    MockSchemaRegistryClient client = new MockSchemaRegistryClient();
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.setSchemaRegistry(client);
    SchemaAndValue input = converter.toConnectData("test", client.getData());
    SnowflakeRecordContent content = (SnowflakeRecordContent) input.value();
    assert content.getData().length == 1;
    assert content.getData()[0].asText().equals(mapper.readTree("{\"int" +
      "\":1234}").asText());

    //null value
    input = converter.toConnectData("test",null);
    assert ((SnowflakeRecordContent) input.value()).getData()[0].toString().equals("{}");

  }

  @Test
  public void testAvroWithSchemaRegistryByteInput() throws IOException
  {
    // Define AVRO Schema
    org.apache.avro.Schema decimalType = LogicalTypes.decimal(20, 4)
      .addToSchema(org.apache.avro.Schema.create(org.apache.avro.Schema.Type.BYTES));
    org.apache.avro.Schema schemaWithDecimal = org.apache.avro.SchemaBuilder
      .record("MyRecord").fields()
      .name("bytesDecimal").type(decimalType).noDefault()
      .endRecord();
    System.out.println(schemaWithDecimal.toString(true));

    // Create AVRO object with the schema
    BigDecimal testDecimal = new BigDecimal("90.0000");
    BigInteger testInt = testDecimal.unscaledValue();
    GenericRecord avroRecord = new GenericData.Record(schemaWithDecimal);
    avroRecord.put("bytesDecimal", testInt.toByteArray());

    // Verify that byte representation of unscaled BigDecimal(90.0000) is equivalent with BigInteger("0DBBA0", 16)
    assert Arrays.equals(testInt.toByteArray(), new BigInteger("0DBBA0", 16).toByteArray());

    // Convert AVRO data to Kafka Connect Data for AVRO converter
    AvroData avroData = new AvroData(100);
    SchemaAndValue schemaAndValue = avroData.toConnectData(schemaWithDecimal, avroRecord);

    // Use Confluent AVRO converter to convert AVRO data into byte array
    SchemaRegistryClient schemaRegistry = new io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData(TEST_TOPIC, schemaAndValue.schema(), schemaAndValue.value());

    // The byte array "converted" in the previous line is the value that gets stored in Kafka in a real cluster.
    System.out.println(Hex.encodeHexString(converted)); // 0000000001060dbba0, 0dbba0 is the encoding for the BigDecimal

    // Use Snowflake AVRO converter to convert byte array to JSON
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.setSchemaRegistry(schemaRegistry);
    SchemaAndValue avroInputValue = converter.toConnectData(TEST_TOPIC, converted);
    SnowflakeRecordContent content = (SnowflakeRecordContent) avroInputValue.value();

    // This string is exactly what will appear in Snowflake Database.
    assert content.getData()[0].toString()
      .equals("{\"bytesDecimal\":90.0}");
  }

  @Test
  public void testBrokenRecord() throws IOException
  {
    byte[] data = "fasfas".getBytes(StandardCharsets.UTF_8);
    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue result = converter.toConnectData("test", data);
    assert ((SnowflakeRecordContent) result.value()).isBroken();
    assert Arrays.equals(data,
      ((SnowflakeRecordContent) result.value()).getBrokenData());

    converter = new SnowflakeAvroConverter();
    result = converter.toConnectData("test", data);
    assert ((SnowflakeRecordContent) result.value()).isBroken();
    assert Arrays.equals(data,
      ((SnowflakeRecordContent) result.value()).getBrokenData());

    MockSchemaRegistryClient client = new MockSchemaRegistryClient();

    byte[] brokenAvroData = new byte[] {(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01};
    result = converter.toConnectData("test", brokenAvroData);
    assert ((SnowflakeRecordContent) result.value()).isBroken();
    assert Arrays.equals(brokenAvroData,
      ((SnowflakeRecordContent) result.value()).getBrokenData());

    ((SnowflakeAvroConverter) converter).setSchemaRegistry(client);
    result = converter.toConnectData("test", brokenAvroData);
    assert ((SnowflakeRecordContent) result.value()).isBroken();
    assert Arrays.equals(brokenAvroData,
      ((SnowflakeRecordContent) result.value()).getBrokenData());

    converter = new SnowflakeAvroConverterWithoutSchemaRegistry();
    result = converter.toConnectData("test", data);
    assert ((SnowflakeRecordContent) result.value()).isBroken();
    assert Arrays.equals(data,
      ((SnowflakeRecordContent) result.value()).getBrokenData());
  }

  @Test
  public void testConnectJsonConverter_MapInt64() throws JsonProcessingException {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Collections.singletonMap("schemas.enable", false);
    jsonConverter.configure(config, false);
    Map<String, Object> jsonMap = new HashMap<>();
    // Value will map to int64.
    jsonMap.put("test", Integer.MAX_VALUE);
    SchemaAndValue schemaAndValue =
        jsonConverter.toConnectData("test", mapper.writeValueAsBytes(jsonMap));
    JsonNode result = RecordService.convertToJson(schemaAndValue.schema(), schemaAndValue.value());

    ObjectNode expected = mapper.createObjectNode();
    expected.put("test", Integer.MAX_VALUE);
    assert expected.toString().equals(result.toString());
  }

  @Test
  public void testConnectJsonConverter_MapBigDecimalExceedsMaxPrecision() throws JsonProcessingException {
    JsonConverter jsonConverter = new JsonConverter();
    Map<String, ?> config = Collections.singletonMap("schemas.enable", false);
    jsonConverter.configure(config, false);
    Map<String, Object> jsonMap = new HashMap<>();
    jsonMap.put("test", new BigDecimal("999999999999999999999999999999999999999"));
    SchemaAndValue schemaAndValue =
        jsonConverter.toConnectData("test", mapper.writeValueAsBytes(jsonMap));
    JsonNode result = RecordService.convertToJson(schemaAndValue.schema(), schemaAndValue.value());

    ObjectNode expected = mapper.createObjectNode();
    expected.put("test", new BigDecimal("999999999999999999999999999999999999999"));
    //TODO: uncomment it once KAFKA-10457 is merged
    //assert expected.toString().equals(result.toString());
  }

  @Test
  public void testAvroConverterConfig() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.configure(Collections.singletonMap("schema.registry.url", "http://fake-url"), false);

    Map<String, ?> config = Collections.singletonMap("schema.registry.url", "mock://my-scope-name");
    converter.readBreakOnSchemaRegistryError(config);
    assert !converter.getBreakOnSchemaRegistryError();

    config = Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "true");
    converter.readBreakOnSchemaRegistryError(config);
    assert converter.getBreakOnSchemaRegistryError();

    config = Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "trueeee");
    converter.readBreakOnSchemaRegistryError(config);
    assert !converter.getBreakOnSchemaRegistryError();

    config = Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "True");
    converter.readBreakOnSchemaRegistryError(config);
    assert converter.getBreakOnSchemaRegistryError();
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testAvroConverterErrorConfig() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    converter.configure(new HashMap<String, String>(), true);
  }

  @Test(expected = SnowflakeKafkaConnectorException.class)
  public void testAvroConverterSchemaRegistryErrorFail() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();
    Map<String, ?> config = Collections.singletonMap(SnowflakeAvroConverter.BREAK_ON_SCHEMA_REGISTRY_ERROR, "true");
    converter.readBreakOnSchemaRegistryError(config);

    SchemaBuilder builder = SchemaBuilder.struct()
      .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build());
    Schema schema = builder.build();
    Struct original = new Struct(schema).put("int8", (byte) 12);
    SchemaRegistryClient schemaRegistry = new io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData("test", original.schema(), original);
    // This line will throw expected exception
    SchemaAndValue result = converter.toConnectData("test", converted);
  }

  @Test
  public void testAvroConverterSchemaRegistryErrorContinue() {
    SnowflakeAvroConverter converter = new SnowflakeAvroConverter();

    SchemaBuilder builder = SchemaBuilder.struct()
      .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build());
    Schema schema = builder.build();
    Struct original = new Struct(schema).put("int8", (byte) 12);
    SchemaRegistryClient schemaRegistry = new io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData("test", original.schema(), original);
    SchemaAndValue result = converter.toConnectData("test", converted);
    assert ((SnowflakeRecordContent)result.value()).isBroken();
  }
}
