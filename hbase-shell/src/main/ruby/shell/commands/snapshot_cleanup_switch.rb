#
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Switch snapshot auto-cleanup based on TTL expiration

module Shell
  module Commands
    class SnapshotCleanupSwitch < Command
      def help
        <<-EOF
Enable/Disable snapshot auto-cleanup based on snapshot TTL.
Returns previous snapshot auto-cleanup switch state.
Examples:

  hbase> snapshot_cleanup_switch true
  hbase> snapshot_cleanup_switch false
        EOF
      end

      def command(enable_disable)
        prev_state = admin.snapshot_cleanup_switch(enable_disable) ? 'true' : 'false'
        formatter.row(["Previous snapshot cleanup state : #{prev_state}"])
        prev_state
      end
    end
  end
end
