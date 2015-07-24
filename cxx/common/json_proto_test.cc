/*
 * Copyright 2015 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "json_proto.h"

#include "glog/logging.h"
#include "gtest/gtest.h"
#include "kythe/proto/analysis.pb.h"
#include "kythe/proto/storage.pb.h"

namespace kythe {
namespace {
TEST(JsonProto, Serialize) {
  proto::FileData file_data;
  std::string data_out;
  ASSERT_TRUE(WriteMessageAsJsonToString(file_data, "kythe", &data_out));
  EXPECT_EQ("{\"format\":\"kythe\",\"content\":{}}", data_out);
  file_data.set_content("text");
  file_data.mutable_info()->set_path("here");
  data_out.clear();
  ASSERT_TRUE(WriteMessageAsJsonToString(file_data, "kythe", &data_out));
  EXPECT_EQ(
      "{\"format\":\"kythe\",\"content\":{\"content\":\"dGV4dA==\",\"info\":{"
      "\"path\":\"here\"}}}",
      data_out);
  proto::SearchReply has_repeated_field;
  data_out.clear();
  ASSERT_TRUE(
      WriteMessageAsJsonToString(has_repeated_field, "kythe", &data_out));
  EXPECT_EQ("{\"format\":\"kythe\",\"content\":{}}", data_out);
  has_repeated_field.add_ticket("1");
  has_repeated_field.add_ticket("2");
  data_out.clear();
  ASSERT_TRUE(
      WriteMessageAsJsonToString(has_repeated_field, "kythe", &data_out));
  EXPECT_EQ(
      "{\"format\":\"kythe\",\"content\":{\"ticket\":[\"1\",\"2\"]}}",
      data_out);
}

TEST(JsonProto, Deserialize) {
  proto::FileData file_data;
  std::string format_string;
  ASSERT_FALSE(MergeJsonWithMessage("{}", &format_string, &file_data));
  ASSERT_FALSE(MergeJsonWithMessage("{\"format\":{},\"content\":{}}",
                                    &format_string, &file_data));
  ASSERT_FALSE(MergeJsonWithMessage("{\"format\":\"wrong\",\"content\":{}}",
                                    &format_string, &file_data));
  ASSERT_FALSE(
      MergeJsonWithMessage("{\"content\":{}}", &format_string, &file_data));
  ASSERT_TRUE(MergeJsonWithMessage("{\"format\":\"kythe\",\"content\":{}}",
                                   &format_string, &file_data));
  EXPECT_EQ("kythe", format_string);
  ASSERT_TRUE(MergeJsonWithMessage(
      "{\"format\":\"kythe\",\"content\":{\"content\":\"dGV4dA==\",\"info\":{"
      "\"path\":\"here\"}}}",
      &format_string, &file_data));
  EXPECT_EQ("text", file_data.content());
  EXPECT_EQ("here", file_data.info().path());
  proto::SearchReply has_repeated_field;
  ASSERT_TRUE(MergeJsonWithMessage(
      "{\"format\":\"kythe\",\"content\":{\"ticket\":[]}}", &format_string,
      &has_repeated_field));
  EXPECT_EQ(0, has_repeated_field.ticket_size());
  ASSERT_TRUE(MergeJsonWithMessage(
      "{\"format\":\"kythe\",\"content\":{\"ticket\":[\"1\",\"2\"]}}",
      &format_string, &has_repeated_field));
  EXPECT_EQ(2, has_repeated_field.ticket_size());
  EXPECT_EQ("1", has_repeated_field.ticket(0));
  EXPECT_EQ("2", has_repeated_field.ticket(1));
}

TEST(JsonProto, Encode64) {
  EXPECT_EQ("aGVsbG8K", EncodeBase64("hello\n"));
  EXPECT_EQ("", EncodeBase64(""));
}

TEST(JsonProto, Decode64) {
  google::protobuf::string buffer;
  EXPECT_TRUE(DecodeBase64("aGVsbG8K", &buffer));
  EXPECT_EQ("hello\n", buffer);
  EXPECT_TRUE(DecodeBase64("YnllCg==", &buffer));
  EXPECT_EQ("bye\n", buffer);
  EXPECT_TRUE(DecodeBase64("Y2lhbwo=", &buffer));
  EXPECT_EQ("ciao\n", buffer);
  EXPECT_TRUE(DecodeBase64("", &buffer));
  EXPECT_EQ("", buffer);
  // Check to make sure that the function tolerates bad input.
  DecodeBase64("==", &buffer);
  DecodeBase64("=", &buffer);
  DecodeBase64("===", &buffer);
  DecodeBase64("!", &buffer);
}

}  // namespace
}  // namespace kythe

int main(int argc, char **argv) {
  GOOGLE_PROTOBUF_VERIFY_VERSION;
  google::InitGoogleLogging(argv[0]);
  ::testing::InitGoogleTest(&argc, argv);
  int result = RUN_ALL_TESTS();
  return result;
}
