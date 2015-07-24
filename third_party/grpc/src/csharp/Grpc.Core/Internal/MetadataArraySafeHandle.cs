#region Copyright notice and license
// Copyright 2015, Google Inc.
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
#endregion
using System;
using System.Runtime.InteropServices;
using System.Threading.Tasks;

namespace Grpc.Core.Internal
{
    /// <summary>
    /// grpc_metadata_array from <grpc/grpc.h>
    /// </summary>
    internal class MetadataArraySafeHandle : SafeHandleZeroIsInvalid
    {
        [DllImport("grpc_csharp_ext.dll")]
        static extern MetadataArraySafeHandle grpcsharp_metadata_array_create(UIntPtr capacity);

        [DllImport("grpc_csharp_ext.dll", CharSet = CharSet.Ansi)]
        static extern void grpcsharp_metadata_array_add(MetadataArraySafeHandle array, string key, byte[] value, UIntPtr valueLength);

        [DllImport("grpc_csharp_ext.dll")]
        static extern void grpcsharp_metadata_array_destroy_full(IntPtr array);

        private MetadataArraySafeHandle()
        {
        }

        public static MetadataArraySafeHandle Create(Metadata metadata)
        {
            var entries = metadata.Entries;
            var metadataArray = grpcsharp_metadata_array_create(new UIntPtr((ulong)entries.Count));
            for (int i = 0; i < entries.Count; i++)
            {
                grpcsharp_metadata_array_add(metadataArray, entries[i].Key, entries[i].ValueBytes, new UIntPtr((ulong)entries[i].ValueBytes.Length));
            }
            return metadataArray;
        }

        protected override bool ReleaseHandle()
        {
            grpcsharp_metadata_array_destroy_full(handle);
            return true;
        }
    }
}
