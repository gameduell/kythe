/*
 * Copyright 2014 Google Inc. All rights reserved.
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

package com.google.devtools.kythe.analyzers.base;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.kythe.common.FormattingLogger;
import com.google.devtools.kythe.proto.Storage.VName;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Set of storage entries with a common (source, edgeKind, target) tuple.
 *
 * The signature of source {@link VName} may be explicitly set or be determined by the set of
 * properties in the {@link EntrySet} along with a set of salts.
 */
public class EntrySet {
  private static final FormattingLogger logger =
      FormattingLogger.getLogger(EntrySet.class);

  /** {@link Charset} used to encode {@link String} property values. */
  public static final Charset PROPERTY_VALUE_CHARSET = StandardCharsets.UTF_8;

  /**
   * Map with only the "empty" property. This is used since a (source, edge, target) tuple may only
   * be emitted if there is at least one associated property set.
   */
  private static final ImmutableMap<String, byte[]> EMPTY_PROPERTIES =
      ImmutableMap.<String, byte[]>builder().put("/", new byte[0]).build();

  // invariant: source != null && ((edgeKind == null) == (target == null))
  private final VName source;
  private final String edgeKind;
  private final VName target;

  // invariant: !properties.isEmpty()
  private final ImmutableMap<String, byte[]> properties;

  /** Used to detect when an {@link EntrySet} was not emitted exactly once. */
  private boolean emitted;

  protected EntrySet(VName source, String edgeKind, VName target,
      ImmutableMap<String, byte[]> properties) {
    Preconditions.checkArgument((edgeKind == null) == (target == null),
        "edgeKind and target must be both non-null or both null");
    this.source = source;
    this.edgeKind = edgeKind;
    this.target = target;
    if (properties.isEmpty()) {
      this.properties = EMPTY_PROPERTIES;
    } else {
      this.properties = properties;
    }
  }

  /** Returns the {@link EntrySet}'s source {@link VName}. */
  public VName getVName() {
    return source;
  }

  /** Emits each entry in the {@link EntrySet} using the given {@link FactEmitter}. */
  public void emit(FactEmitter emitter) {
    if (emitted) {
      logger.warningfmt("EntrySet already emitted: %s", this);
    }
    for (Map.Entry<String, byte[]> entry : properties.entrySet()) {
      emitter.emit(source, edgeKind, target, entry.getKey(), entry.getValue());
    }
    emitted = true;
  }

  /**
   * Using the first {@link VName} as a base, merge the specified fields from the {@code extension}
   * {@link VName} into a new {@link VName}.
   */
  public static VName extendVName(VName base, VName extension) {
    return VName.newBuilder(base).mergeFrom(extension).build();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("EntrySet {\n");
    builder.append("Source: {" + source + "}\n");
    if (edgeKind != null) {
      builder.append("Target: {" + target + "}\n");
      builder.append("EdgeKind: " + edgeKind);
    }
    for (Map.Entry<String, byte[]> entry : properties.entrySet()) {
      builder.append(String.format("%s %s\n",
              entry.getKey(), new String(entry.getValue(), PROPERTY_VALUE_CHARSET)));
    }
    return builder.append("}").toString();
  }

  @Override
  public void finalize() {
    if (!emitted) {
      logger.severefmt("EntrySet finalized before being emitted: " + this);
    }
  }

  /**
   * Builder for arbitrary {@link EntrySet}s. Meant to be sub-classed for building specialized
   * {@link EntrySet}s (like Kythe nodes and edges).
   *
   * @see KytheEntrySets.NodeBuilder
   * @see KytheEntrySets.EdgeBuilder
   */
  public static class Builder {
    private final ImmutableSortedMap.Builder<String, byte[]> properties =
        ImmutableSortedMap.naturalOrder();

    private String propertyPrefix = "";

    // invariant: (source == null) ^ (sourceBuilder == null && salts.isEmpty())
    protected final ImmutableList.Builder<String> salts = ImmutableList.builder();
    protected final VName.Builder sourceBuilder;
    private final VName source;

    // invariant: (edgeKind == null) == (target == null)
    private final String edgeKind;
    private final VName target;

    /**
     * Construct a new {@link EdgeSet.Builder} with the given (source, edgeKind, target) tuple. This
     * is meant to begin building an edge {@link EdgeSet}.
     *
     * @see KytheEntrySets.EdgeBuilder
     */
    public Builder(VName source, String edgeKind, VName target) {
      this.sourceBuilder = null;
      this.source = source;
      this.edgeKind = edgeKind;
      this.target = target;
    }

    /**
     * Construct a new {@link EdgeSet.Builder} with the given base for a source {@link VName} and
     * {@code null} edgeKind/target.  This is meant to begin building a node {@link EdgeSet}.
     *
     * @see KytheEntrySets.NodeBuilder
     */
    public Builder(VName.Builder sourceBase) {
      this.sourceBuilder = sourceBase;
      this.source = null;
      this.edgeKind = null;
      this.target = null;
    }

    /** Sets a prefix for all further properties set. */
    protected void setPropertyPrefix(String prefix) {
      this.propertyPrefix = prefix;
    }

    /** Sets a named property value to the {@link EntrySet.Builder}. */
    public Builder setProperty(String name, byte[] value) {
      Preconditions.checkNotNull(name, "name must be non-null");
      Preconditions.checkNotNull(value, "value must be non-null");
      properties.put(propertyPrefix + name, value);
      return this;
    }

    /**
     * Sets a named property value to the {@link EntrySet.Builder}. The property value will be
     * coerced to a {@code byte[]} before it is added.
     *
     * @see #setProperty(String, byte[])
     * @see EntrySet#PROPERTY_VALUE_CHARSET
     */
    public Builder setProperty(String name, String value) {
      return setProperty(name, value.getBytes(PROPERTY_VALUE_CHARSET));
    }

    /** Builds a new {@link EntrySet}. */
    public EntrySet build() {
      ImmutableSortedMap<String, byte[]> properties = this.properties.build();
      VName source = this.source;
      if (source == null) {
        if (!sourceBuilder.getSignature().isEmpty()) {
          source = sourceBuilder.build();
        } else {
          source = sourceBuilder.clone()
              .setSignature(buildSignature(salts.build(), properties)).build();
        }
      }
      return new EntrySet(source, edgeKind, target, properties);
    }
  }

  private static final HashFunction SIGNATURE_HASH_FUNCTION = Hashing.sha256();

  protected static String buildSignature(ImmutableList<String> salts,
      ImmutableSortedMap<String, byte[]> properties) {
    Hasher signature = SIGNATURE_HASH_FUNCTION.newHasher();
    for (String salt : salts) {
      signature.putString(salt, PROPERTY_VALUE_CHARSET);
    }
    for (Map.Entry<String, byte[]> property : properties.entrySet()) {
      signature
          .putString(property.getKey(), PROPERTY_VALUE_CHARSET)
          .putBytes(property.getValue());
    }
    return signature.hash().toString();
  }
}
