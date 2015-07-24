#!/bin/bash -e
# Copyright 2015 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# This test checks that transcripts record useful changes in the environment.
# Specifically, the main source file transcript for the _without.UNIT file
# should differ from the main source file transcript for the _with.UNIT file.
# It should be run from the Kythe root.
TEST_NAME="test_main_source_file_env_dep"
. ./kythe/cxx/extractor/testdata/test_common.sh
INDEX_WITH_MACRO="092175fd1d6172588ef89d4d6a0979460fef9eaa5b28b32fde5f38895962aab0.kindex"
INDEX_WITHOUT_MACRO="dffcbf93cf2aa88c7a32245e36e0645c481edcfbfb6484bb6ee0d2b0363439a7.kindex"
INDEX_PATH_WITH_MACRO="${OUT_DIR}"/"${INDEX_WITH_MACRO}"
INDEX_PATH_WITHOUT_MACRO="${OUT_DIR}"/"${INDEX_WITHOUT_MACRO}"
rm -f -- "${INDEX_PATH_WITH_MACRO}_UNIT" "${INDEX_PATH_WITHOUT_MACRO}_UNIT"
KYTHE_OUTPUT_DIRECTORY="${OUT_DIR}" \
    "${EXTRACTOR}" --with_executable "/usr/bin/g++" \
    -I./kythe/cxx/extractor/testdata \
    ./kythe/cxx/extractor/testdata/main_source_file_env_dep.cc
KYTHE_OUTPUT_DIRECTORY="${OUT_DIR}" \
    "${EXTRACTOR}" --with_executable "/usr/bin/g++" \
    -I./kythe/cxx/extractor/testdata -DMACRO \
    ./kythe/cxx/extractor/testdata/main_source_file_env_dep.cc
"${KINDEX_TOOL}" -suppress_details -explode "${INDEX_PATH_WITH_MACRO}"
"${KINDEX_TOOL}" -suppress_details -explode "${INDEX_PATH_WITHOUT_MACRO}"
EC_HASH=$(sed -ne '/^entry_context:/ {s/.*entry_context: \"\(.*\)\"$/\1/; p;}' \
    "${INDEX_PATH_WITH_MACRO}_UNIT")
sed "s/EC_HASH/${EC_HASH}/g" \
    "${BASE_DIR}/main_source_file_env_dep_with.UNIT" \
    | diff - "${INDEX_PATH_WITH_MACRO}_UNIT"
EC_HASH=$(sed -ne '/^entry_context:/ {s/.*entry_context: \"\(.*\)\"$/\1/; p;}' \
    "${INDEX_PATH_WITHOUT_MACRO}_UNIT")
sed "s/EC_HASH/${EC_HASH}/g" \
    "${BASE_DIR}/main_source_file_env_dep_without.UNIT" \
    | diff - "${INDEX_PATH_WITHOUT_MACRO}_UNIT"
