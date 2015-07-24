#!/bin/bash -e

# Copyright 2014 Google Inc. All rights reserved.
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

# extract_compilation_database.sh will run available Kythe extractors on
# compilation databases, documented at
#   <http://clang.llvm.org/docs/JSONCompilationDatabase.html>.
# Expects to be run at the Kythe root directory.
CXX_EXTRACTOR="kythe/cxx/extractor/cxx_extractor"
JQ="third_party/jq/jq"
DATABASE="$1"
FILTER=".[]|.command"
: ${KYTHE_CORPUS:?Missing environment variable}
: ${KYTHE_ROOT_DIRECTORY:?Missing environment variable}
: ${KYTHE_OUTPUT_DIRECTORY:?Missing environment variable}
: ${DATABASE:?Missing database (use: ${0} path/to/compile_commands.json)}
${JQ} -r "${FILTER}" "${DATABASE}" \
    | sed "s:^:${CXX_EXTRACTOR} --with_executable :" | parallel --gnu
