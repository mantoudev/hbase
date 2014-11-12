/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import static org.apache.hadoop.hbase.util.Bytes.len;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.util.StreamUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.RawComparator;

import com.google.common.annotations.VisibleForTesting;

/**
 * An HBase Key/Value. This is the fundamental HBase Type.  
 * <p>
 * HBase applications and users should use the Cell interface and avoid directly using KeyValue
 * and member functions not defined in Cell.
 * <p>
 * If being used client-side, the primary methods to access individual fields are {@link #getRow()},
 * {@link #getFamily()}, {@link #getQualifier()}, {@link #getTimestamp()}, and {@link #getValue()}.
 * These methods allocate new byte arrays and return copies. Avoid their use server-side.
 * <p>
 * Instances of this class are immutable. They do not implement Comparable but Comparators are
 * provided. Comparators change with context, whether user table or a catalog table comparison. Its
 * critical you use the appropriate comparator. There are Comparators for normal HFiles, Meta's
 * Hfiles, and bloom filter keys.
 * <p>
 * KeyValue wraps a byte array and takes offsets and lengths into passed array at where to start
 * interpreting the content as KeyValue. The KeyValue format inside a byte array is:
 * <code>&lt;keylength> &lt;valuelength> &lt;key> &lt;value></code> Key is further decomposed as:
 * <code>&lt;rowlength> &lt;row> &lt;columnfamilylength> &lt;columnfamily> &lt;columnqualifier>
 * &lt;timestamp> &lt;keytype></code>
 * The <code>rowlength</code> maximum is <code>Short.MAX_SIZE</code>, column family length maximum
 * is <code>Byte.MAX_SIZE</code>, and column qualifier + key length must be <
 * <code>Integer.MAX_SIZE</code>. The column does not contain the family/qualifier delimiter,
 * {@link #COLUMN_FAMILY_DELIMITER}<br>
 * KeyValue can optionally contain Tags. When it contains tags, it is added in the byte array after
 * the value part. The format for this part is: <code>&lt;tagslength>&lt;tagsbytes></code>.
 * <code>tagslength</code> maximum is <code>Short.MAX_SIZE</code>. The <code>tagsbytes</code>
 * contain one or more tags where as each tag is of the form
 * <code>&lt;taglength>&lt;tagtype>&lt;tagbytes></code>.  <code>tagtype</code> is one byte and
 * <code>taglength</code> maximum is <code>Short.MAX_SIZE</code> and it includes 1 byte type length
 * and actual tag bytes length.
 */
@InterfaceAudience.Private
public class KeyValue implements Cell, HeapSize, Cloneable, SettableSequenceId, SettableTimestamp {
  private static final ArrayList<Tag> EMPTY_ARRAY_LIST = new ArrayList<Tag>();

  static final Log LOG = LogFactory.getLog(KeyValue.class);

  /**
   * Colon character in UTF-8
   */
  public static final char COLUMN_FAMILY_DELIMITER = ':';

  public static final byte[] COLUMN_FAMILY_DELIM_ARRAY =
    new byte[]{COLUMN_FAMILY_DELIMITER};

  /**
   * Comparator for plain key/values; i.e. non-catalog table key/values. Works on Key portion
   * of KeyValue only.
   */
  public static final KVComparator COMPARATOR = new KVComparator();
  /**
   * A {@link KVComparator} for <code>hbase:meta</code> catalog table
   * {@link KeyValue}s.
   */
  public static final KVComparator META_COMPARATOR = new MetaComparator();

  /**
   * Needed for Bloom Filters.
   */
  public static final KVComparator RAW_COMPARATOR = new RawBytesComparator();

  /** Size of the key length field in bytes*/
  public static final int KEY_LENGTH_SIZE = Bytes.SIZEOF_INT;

  /** Size of the key type field in bytes */
  public static final int TYPE_SIZE = Bytes.SIZEOF_BYTE;

  /** Size of the row length field in bytes */
  public static final int ROW_LENGTH_SIZE = Bytes.SIZEOF_SHORT;

  /** Size of the family length field in bytes */
  public static final int FAMILY_LENGTH_SIZE = Bytes.SIZEOF_BYTE;

  /** Size of the timestamp field in bytes */
  public static final int TIMESTAMP_SIZE = Bytes.SIZEOF_LONG;

  // Size of the timestamp and type byte on end of a key -- a long + a byte.
  public static final int TIMESTAMP_TYPE_SIZE = TIMESTAMP_SIZE + TYPE_SIZE;

  // Size of the length shorts and bytes in key.
  public static final int KEY_INFRASTRUCTURE_SIZE = ROW_LENGTH_SIZE
      + FAMILY_LENGTH_SIZE + TIMESTAMP_TYPE_SIZE;

  // How far into the key the row starts at. First thing to read is the short
  // that says how long the row is.
  public static final int ROW_OFFSET =
    Bytes.SIZEOF_INT /*keylength*/ +
    Bytes.SIZEOF_INT /*valuelength*/;

  // Size of the length ints in a KeyValue datastructure.
  public static final int KEYVALUE_INFRASTRUCTURE_SIZE = ROW_OFFSET;

  /** Size of the tags length field in bytes */
  public static final int TAGS_LENGTH_SIZE = Bytes.SIZEOF_SHORT;

  public static final int KEYVALUE_WITH_TAGS_INFRASTRUCTURE_SIZE = ROW_OFFSET + TAGS_LENGTH_SIZE;

  private static final int MAX_TAGS_LENGTH = (2 * Short.MAX_VALUE) + 1;

  /**
   * Computes the number of bytes that a <code>KeyValue</code> instance with the provided
   * characteristics would take up for its underlying data structure.
   *
   * @param rlength row length
   * @param flength family length
   * @param qlength qualifier length
   * @param vlength value length
   *
   * @return the <code>KeyValue</code> data structure length
   */
  public static long getKeyValueDataStructureSize(int rlength,
      int flength, int qlength, int vlength) {
    return KeyValue.KEYVALUE_INFRASTRUCTURE_SIZE
        + getKeyDataStructureSize(rlength, flength, qlength) + vlength;
  }

  /**
   * Computes the number of bytes that a <code>KeyValue</code> instance with the provided
   * characteristics would take up for its underlying data structure.
   *
   * @param rlength row length
   * @param flength family length
   * @param qlength qualifier length
   * @param vlength value length
   * @param tagsLength total length of the tags
   *
   * @return the <code>KeyValue</code> data structure length
   */
  public static long getKeyValueDataStructureSize(int rlength, int flength, int qlength,
      int vlength, int tagsLength) {
    if (tagsLength == 0) {
      return getKeyValueDataStructureSize(rlength, flength, qlength, vlength);
    }
    return KeyValue.KEYVALUE_WITH_TAGS_INFRASTRUCTURE_SIZE
        + getKeyDataStructureSize(rlength, flength, qlength) + vlength + tagsLength;
  }

  /**
   * Computes the number of bytes that a <code>KeyValue</code> instance with the provided
   * characteristics would take up for its underlying data structure.
   *
   * @param klength key length
   * @param vlength value length
   * @param tagsLength total length of the tags
   *
   * @return the <code>KeyValue</code> data structure length
   */
  public static long getKeyValueDataStructureSize(int klength, int vlength, int tagsLength) {
    if (tagsLength == 0) {
      return KeyValue.KEYVALUE_INFRASTRUCTURE_SIZE + klength + vlength;
    }
    return KeyValue.KEYVALUE_WITH_TAGS_INFRASTRUCTURE_SIZE + klength + vlength + tagsLength;
  }

  /**
   * Computes the number of bytes that a <code>KeyValue</code> instance with the provided
   * characteristics would take up in its underlying data structure for the key.
   *
   * @param rlength row length
   * @param flength family length
   * @param qlength qualifier length
   *
   * @return the key data structure length
   */
  public static long getKeyDataStructureSize(int rlength, int flength, int qlength) {
    return KeyValue.KEY_INFRASTRUCTURE_SIZE + rlength + flength + qlength;
  }

  /**
   * Key type.
   * Has space for other key types to be added later.  Cannot rely on
   * enum ordinals . They change if item is removed or moved.  Do our own codes.
   */
  public static enum Type {
    Minimum((byte)0),
    Put((byte)4),

    Delete((byte)8),
    DeleteFamilyVersion((byte)10),
    DeleteColumn((byte)12),
    DeleteFamily((byte)14),

    // Maximum is used when searching; you look from maximum on down.
    Maximum((byte)255);

    private final byte code;

    Type(final byte c) {
      this.code = c;
    }

    public byte getCode() {
      return this.code;
    }

    /**
     * Cannot rely on enum ordinals . They change if item is removed or moved.
     * Do our own codes.
     * @param b
     * @return Type associated with passed code.
     */
    public static Type codeToType(final byte b) {
      for (Type t : Type.values()) {
        if (t.getCode() == b) {
          return t;
        }
      }
      throw new RuntimeException("Unknown code " + b);
    }
  }

  /**
   * Lowest possible key.
   * Makes a Key with highest possible Timestamp, empty row and column.  No
   * key can be equal or lower than this one in memstore or in store file.
   */
  public static final KeyValue LOWESTKEY =
    new KeyValue(HConstants.EMPTY_BYTE_ARRAY, HConstants.LATEST_TIMESTAMP);

  ////
  // KeyValue core instance fields.
  private byte [] bytes = null;  // an immutable byte array that contains the KV
  private int offset = 0;  // offset into bytes buffer KV starts at
  private int length = 0;  // length of the KV starting from offset.

  /**
   * @return True if a delete type, a {@link KeyValue.Type#Delete} or
   * a {KeyValue.Type#DeleteFamily} or a {@link KeyValue.Type#DeleteColumn}
   * KeyValue type.
   */
  public static boolean isDelete(byte t) {
    return Type.Delete.getCode() <= t && t <= Type.DeleteFamily.getCode();
  }

  /** Here be dragons **/

  // used to achieve atomic operations in the memstore.
  @Override
  public long getMvccVersion() {
    return this.getSequenceId();
  }

  /**
   * used to achieve atomic operations in the memstore.
   */
  @Override
  public long getSequenceId() {
    return seqId;
  }

  public void setSequenceId(long seqId) {
    this.seqId = seqId;
  }

  // multi-version concurrency control version.  default value is 0, aka do not care.
  private long seqId = 0;

  /** Dragon time over, return to normal business */


  /** Writable Constructor -- DO NOT USE */
  public KeyValue() {}

  /**
   * Creates a KeyValue from the start of the specified byte array.
   * Presumes <code>bytes</code> content is formatted as a KeyValue blob.
   * @param bytes byte array
   */
  public KeyValue(final byte [] bytes) {
    this(bytes, 0);
  }

  /**
   * Creates a KeyValue from the specified byte array and offset.
   * Presumes <code>bytes</code> content starting at <code>offset</code> is
   * formatted as a KeyValue blob.
   * @param bytes byte array
   * @param offset offset to start of KeyValue
   */
  public KeyValue(final byte [] bytes, final int offset) {
    this(bytes, offset, getLength(bytes, offset));
  }

  /**
   * Creates a KeyValue from the specified byte array, starting at offset, and
   * for length <code>length</code>.
   * @param bytes byte array
   * @param offset offset to start of the KeyValue
   * @param length length of the KeyValue
   */
  public KeyValue(final byte [] bytes, final int offset, final int length) {
    this.bytes = bytes;
    this.offset = offset;
    this.length = length;
  }

  /**
   * Creates a KeyValue from the specified byte array, starting at offset, and
   * for length <code>length</code>.
   *
   * @param bytes  byte array
   * @param offset offset to start of the KeyValue
   * @param length length of the KeyValue
   * @param ts
   */
  public KeyValue(final byte[] bytes, final int offset, final int length, long ts) {
    this(bytes, offset, length, null, 0, 0, null, 0, 0, ts, Type.Maximum, null, 0, 0, null);
  }

  /** Constructors that build a new backing byte array from fields */

  /**
   * Constructs KeyValue structure filled with null value.
   * Sets type to {@link KeyValue.Type#Maximum}
   * @param row - row key (arbitrary byte array)
   * @param timestamp
   */
  public KeyValue(final byte [] row, final long timestamp) {
    this(row, null, null, timestamp, Type.Maximum, null);
  }

  /**
   * Constructs KeyValue structure filled with null value.
   * @param row - row key (arbitrary byte array)
   * @param timestamp
   */
  public KeyValue(final byte [] row, final long timestamp, Type type) {
    this(row, null, null, timestamp, type, null);
  }

  /**
   * Constructs KeyValue structure filled with null value.
   * Sets type to {@link KeyValue.Type#Maximum}
   * @param row - row key (arbitrary byte array)
   * @param family family name
   * @param qualifier column qualifier
   */
  public KeyValue(final byte [] row, final byte [] family,
      final byte [] qualifier) {
    this(row, family, qualifier, HConstants.LATEST_TIMESTAMP, Type.Maximum);
  }

  /**
   * Constructs KeyValue structure filled with null value.
   * @param row - row key (arbitrary byte array)
   * @param family family name
   * @param qualifier column qualifier
   */
  public KeyValue(final byte [] row, final byte [] family,
      final byte [] qualifier, final byte [] value) {
    this(row, family, qualifier, HConstants.LATEST_TIMESTAMP, Type.Put, value);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param type key type
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, Type type) {
    this(row, family, qualifier, timestamp, type, null);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param value column value
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, final byte[] value) {
    this(row, family, qualifier, timestamp, Type.Put, value);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param value column value
   * @param tags tags
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, final byte[] value,
      final Tag[] tags) {
    this(row, family, qualifier, timestamp, value, tags != null ? Arrays.asList(tags) : null);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param value column value
   * @param tags tags non-empty list of tags or null
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, final byte[] value,
      final List<Tag> tags) {
    this(row, 0, row==null ? 0 : row.length,
      family, 0, family==null ? 0 : family.length,
      qualifier, 0, qualifier==null ? 0 : qualifier.length,
      timestamp, Type.Put,
      value, 0, value==null ? 0 : value.length, tags);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, Type type,
      final byte[] value) {
    this(row, 0, len(row),   family, 0, len(family),   qualifier, 0, len(qualifier),
        timestamp, type,   value, 0, len(value));
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * <p>
   * Column is split into two fields, family and qualifier.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, Type type,
      final byte[] value, final List<Tag> tags) {
    this(row, family, qualifier, 0, qualifier==null ? 0 : qualifier.length,
        timestamp, type, value, 0, value==null ? 0 : value.length, tags);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte[] row, final byte[] family,
      final byte[] qualifier, final long timestamp, Type type,
      final byte[] value, final byte[] tags) {
    this(row, family, qualifier, 0, qualifier==null ? 0 : qualifier.length,
        timestamp, type, value, 0, value==null ? 0 : value.length, tags);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * @param row row key
   * @param family family name
   * @param qualifier column qualifier
   * @param qoffset qualifier offset
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @param voffset value offset
   * @param vlength value length
   * @throws IllegalArgumentException
   */
  public KeyValue(byte [] row, byte [] family,
      byte [] qualifier, int qoffset, int qlength, long timestamp, Type type,
      byte [] value, int voffset, int vlength, List<Tag> tags) {
    this(row, 0, row==null ? 0 : row.length,
        family, 0, family==null ? 0 : family.length,
        qualifier, qoffset, qlength, timestamp, type,
        value, voffset, vlength, tags);
  }

  /**
   * @param row
   * @param family
   * @param qualifier
   * @param qoffset
   * @param qlength
   * @param timestamp
   * @param type
   * @param value
   * @param voffset
   * @param vlength
   * @param tags
   */
  public KeyValue(byte [] row, byte [] family,
      byte [] qualifier, int qoffset, int qlength, long timestamp, Type type,
      byte [] value, int voffset, int vlength, byte[] tags) {
    this(row, 0, row==null ? 0 : row.length,
        family, 0, family==null ? 0 : family.length,
        qualifier, qoffset, qlength, timestamp, type,
        value, voffset, vlength, tags, 0, tags==null ? 0 : tags.length);
  }

  /**
   * Constructs KeyValue structure filled with specified values.
   * <p>
   * Column is split into two fields, family and qualifier.
   * @param row row key
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte [] row, final int roffset, final int rlength,
      final byte [] family, final int foffset, final int flength,
      final byte [] qualifier, final int qoffset, final int qlength,
      final long timestamp, final Type type,
      final byte [] value, final int voffset, final int vlength) {
    this(row, roffset, rlength, family, foffset, flength, qualifier, qoffset,
      qlength, timestamp, type, value, voffset, vlength, null);
  }
  
  /**
   * Constructs KeyValue structure filled with specified values. Uses the provided buffer as the
   * data buffer.
   * <p>
   * Column is split into two fields, family and qualifier.
   *
   * @param buffer the bytes buffer to use
   * @param boffset buffer offset
   * @param row row key
   * @param roffset row offset
   * @param rlength row length
   * @param family family name
   * @param foffset family offset
   * @param flength family length
   * @param qualifier column qualifier
   * @param qoffset qualifier offset
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @param voffset value offset
   * @param vlength value length
   * @param tags non-empty list of tags or null
   * @throws IllegalArgumentException an illegal value was passed or there is insufficient space
   * remaining in the buffer
   */
  public KeyValue(byte [] buffer, final int boffset,
      final byte [] row, final int roffset, final int rlength,
      final byte [] family, final int foffset, final int flength,
      final byte [] qualifier, final int qoffset, final int qlength,
      final long timestamp, final Type type,
      final byte [] value, final int voffset, final int vlength,
      final Tag[] tags) {
     this.bytes  = buffer;
     this.length = writeByteArray(buffer, boffset,
         row, roffset, rlength,
         family, foffset, flength, qualifier, qoffset, qlength,
        timestamp, type, value, voffset, vlength, tags);
     this.offset = boffset;
   }

  /**
   * Constructs KeyValue structure filled with specified values.
   * <p>
   * Column is split into two fields, family and qualifier.
   * @param row row key
   * @param roffset row offset
   * @param rlength row length
   * @param family family name
   * @param foffset family offset
   * @param flength family length
   * @param qualifier column qualifier
   * @param qoffset qualifier offset
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @param voffset value offset
   * @param vlength value length
   * @param tags tags
   * @throws IllegalArgumentException
   */
  public KeyValue(final byte [] row, final int roffset, final int rlength,
      final byte [] family, final int foffset, final int flength,
      final byte [] qualifier, final int qoffset, final int qlength,
      final long timestamp, final Type type,
      final byte [] value, final int voffset, final int vlength,
      final List<Tag> tags) {
    this.bytes = createByteArray(row, roffset, rlength,
        family, foffset, flength, qualifier, qoffset, qlength,
        timestamp, type, value, voffset, vlength, tags);
    this.length = bytes.length;
    this.offset = 0;
  }

  /**
   * @param row
   * @param roffset
   * @param rlength
   * @param family
   * @param foffset
   * @param flength
   * @param qualifier
   * @param qoffset
   * @param qlength
   * @param timestamp
   * @param type
   * @param value
   * @param voffset
   * @param vlength
   * @param tags
   */
  public KeyValue(final byte [] row, final int roffset, final int rlength,
      final byte [] family, final int foffset, final int flength,
      final byte [] qualifier, final int qoffset, final int qlength,
      final long timestamp, final Type type,
      final byte [] value, final int voffset, final int vlength,
      final byte[] tags, final int tagsOffset, final int tagsLength) {
    this.bytes = createByteArray(row, roffset, rlength,
        family, foffset, flength, qualifier, qoffset, qlength,
        timestamp, type, value, voffset, vlength, tags, tagsOffset, tagsLength);
    this.length = bytes.length;
    this.offset = 0;
  }

  /**
   * Constructs an empty KeyValue structure, with specified sizes.
   * This can be used to partially fill up KeyValues.
   * <p>
   * Column is split into two fields, family and qualifier.
   * @param rlength row length
   * @param flength family length
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param vlength value length
   * @throws IllegalArgumentException
   */
  public KeyValue(final int rlength,
      final int flength,
      final int qlength,
      final long timestamp, final Type type,
      final int vlength) {
    this(rlength, flength, qlength, timestamp, type, vlength, 0);
  }

  /**
   * Constructs an empty KeyValue structure, with specified sizes.
   * This can be used to partially fill up KeyValues.
   * <p>
   * Column is split into two fields, family and qualifier.
   * @param rlength row length
   * @param flength family length
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param vlength value length
   * @param tagsLength
   * @throws IllegalArgumentException
   */
  public KeyValue(final int rlength,
      final int flength,
      final int qlength,
      final long timestamp, final Type type,
      final int vlength, final int tagsLength) {
    this.bytes = createEmptyByteArray(rlength, flength, qlength, timestamp, type, vlength,
        tagsLength);
    this.length = bytes.length;
    this.offset = 0;
  }


  public KeyValue(byte[] row, int roffset, int rlength,
                  byte[] family, int foffset, int flength,
                  ByteBuffer qualifier, long ts, Type type, ByteBuffer value, List<Tag> tags) {
    this.bytes = createByteArray(row, roffset, rlength, family, foffset, flength,
        qualifier, 0, qualifier == null ? 0 : qualifier.remaining(), ts, type,
        value, 0, value == null ? 0 : value.remaining(), tags);
    this.length = bytes.length;
    this.offset = 0;
  }

  public KeyValue(Cell c) {
    this(c.getRowArray(), c.getRowOffset(), (int)c.getRowLength(),
        c.getFamilyArray(), c.getFamilyOffset(), (int)c.getFamilyLength(), 
        c.getQualifierArray(), c.getQualifierOffset(), (int) c.getQualifierLength(), 
        c.getTimestamp(), Type.codeToType(c.getTypeByte()), c.getValueArray(), c.getValueOffset(), 
        c.getValueLength(), c.getTagsArray(), c.getTagsOffset(), c.getTagsLength());
  }

  /**
   * Create a KeyValue that is smaller than all other possible KeyValues
   * for the given row. That is any (valid) KeyValue on 'row' would sort
   * _after_ the result.
   *
   * @param row - row key (arbitrary byte array)
   * @return First possible KeyValue on passed <code>row</code>
   * @deprecated Since 0.99.2. Use {@link KeyValueUtil#createFirstOnRow(byte [])} instead
   */
  @Deprecated
  public static KeyValue createFirstOnRow(final byte [] row) {
    return KeyValueUtil.createFirstOnRow(row, HConstants.LATEST_TIMESTAMP);
  }

  /**
   * Create a KeyValue for the specified row, family and qualifier that would be
   * smaller than all other possible KeyValues that have the same row,family,qualifier.
   * Used for seeking.
   * @param row - row key (arbitrary byte array)
   * @param family - family name
   * @param qualifier - column qualifier
   * @return First possible key on passed <code>row</code>, and column.
   * @deprecated Since 0.99.2. Use {@link KeyValueUtil#createFirstOnRow(byte[], byte[], byte[])}
   * instead
   */
  @Deprecated
  public static KeyValue createFirstOnRow(final byte [] row, final byte [] family,
      final byte [] qualifier) {
    return KeyValueUtil.createFirstOnRow(row, family, qualifier);
  }

  /**
   * Create a KeyValue for the specified row, family and qualifier that would be
   * smaller than all other possible KeyValues that have the same row,
   * family, qualifier.
   * Used for seeking.
   * @param row row key
   * @param roffset row offset
   * @param rlength row length
   * @param family family name
   * @param foffset family offset
   * @param flength family length
   * @param qualifier column qualifier
   * @param qoffset qualifier offset
   * @param qlength qualifier length
   * @return First possible key on passed Row, Family, Qualifier.
   * @deprecated Since 0.99.2. Use {@link KeyValueUtil#createFirstOnRow(byte[], int, int,
   * byte[], int, int, byte[], int, int)} instead
   */
  @Deprecated
  public static KeyValue createFirstOnRow(final byte [] row,
      final int roffset, final int rlength, final byte [] family,
      final int foffset, final int flength, final byte [] qualifier,
      final int qoffset, final int qlength) {
    return new KeyValue(row, roffset, rlength, family,
        foffset, flength, qualifier, qoffset, qlength,
        HConstants.LATEST_TIMESTAMP, Type.Maximum, null, 0, 0);
  }

  /**
   * Create an empty byte[] representing a KeyValue
   * All lengths are preset and can be filled in later.
   * @param rlength
   * @param flength
   * @param qlength
   * @param timestamp
   * @param type
   * @param vlength
   * @return The newly created byte array.
   */
  private static byte[] createEmptyByteArray(final int rlength, int flength,
      int qlength, final long timestamp, final Type type, int vlength, int tagsLength) {
    if (rlength > Short.MAX_VALUE) {
      throw new IllegalArgumentException("Row > " + Short.MAX_VALUE);
    }
    if (flength > Byte.MAX_VALUE) {
      throw new IllegalArgumentException("Family > " + Byte.MAX_VALUE);
    }
    // Qualifier length
    if (qlength > Integer.MAX_VALUE - rlength - flength) {
      throw new IllegalArgumentException("Qualifier > " + Integer.MAX_VALUE);
    }
    checkForTagsLength(tagsLength);
    // Key length
    long longkeylength = getKeyDataStructureSize(rlength, flength, qlength);
    if (longkeylength > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("keylength " + longkeylength + " > " +
        Integer.MAX_VALUE);
    }
    int keylength = (int)longkeylength;
    // Value length
    if (vlength > HConstants.MAXIMUM_VALUE_LENGTH) { // FindBugs INT_VACUOUS_COMPARISON
      throw new IllegalArgumentException("Valuer > " +
          HConstants.MAXIMUM_VALUE_LENGTH);
    }

    // Allocate right-sized byte array.
    byte[] bytes= new byte[(int) getKeyValueDataStructureSize(rlength, flength, qlength, vlength,
        tagsLength)];
    // Write the correct size markers
    int pos = 0;
    pos = Bytes.putInt(bytes, pos, keylength);
    pos = Bytes.putInt(bytes, pos, vlength);
    pos = Bytes.putShort(bytes, pos, (short)(rlength & 0x0000ffff));
    pos += rlength;
    pos = Bytes.putByte(bytes, pos, (byte)(flength & 0x0000ff));
    pos += flength + qlength;
    pos = Bytes.putLong(bytes, pos, timestamp);
    pos = Bytes.putByte(bytes, pos, type.getCode());
    pos += vlength;
    if (tagsLength > 0) {
      pos = Bytes.putAsShort(bytes, pos, tagsLength);
    }
    return bytes;
  }

  /**
   * Checks the parameters passed to a constructor.
   *
   * @param row row key
   * @param rlength row length
   * @param family family name
   * @param flength family length
   * @param qlength qualifier length
   * @param vlength value length
   *
   * @throws IllegalArgumentException an illegal value was passed
   */
  private static void checkParameters(final byte [] row, final int rlength,
      final byte [] family, int flength, int qlength, int vlength)
          throws IllegalArgumentException {
    if (rlength > Short.MAX_VALUE) {
      throw new IllegalArgumentException("Row > " + Short.MAX_VALUE);
    }
    if (row == null) {
      throw new IllegalArgumentException("Row is null");
    }
    // Family length
    flength = family == null ? 0 : flength;
    if (flength > Byte.MAX_VALUE) {
      throw new IllegalArgumentException("Family > " + Byte.MAX_VALUE);
    }
    // Qualifier length
    if (qlength > Integer.MAX_VALUE - rlength - flength) {
      throw new IllegalArgumentException("Qualifier > " + Integer.MAX_VALUE);
    }
    // Key length
    long longKeyLength = getKeyDataStructureSize(rlength, flength, qlength);
    if (longKeyLength > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("keylength " + longKeyLength + " > " +
          Integer.MAX_VALUE);
    }
    // Value length
    if (vlength > HConstants.MAXIMUM_VALUE_LENGTH) { // FindBugs INT_VACUOUS_COMPARISON
      throw new IllegalArgumentException("Value length " + vlength + " > " +
          HConstants.MAXIMUM_VALUE_LENGTH);
    }
  }

  /**
   * Write KeyValue format into the provided byte array.
   *
   * @param buffer the bytes buffer to use
   * @param boffset buffer offset
   * @param row row key
   * @param roffset row offset
   * @param rlength row length
   * @param family family name
   * @param foffset family offset
   * @param flength family length
   * @param qualifier column qualifier
   * @param qoffset qualifier offset
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @param voffset value offset
   * @param vlength value length
   *
   * @return The number of useful bytes in the buffer.
   *
   * @throws IllegalArgumentException an illegal value was passed or there is insufficient space
   * remaining in the buffer
   */
  public static int writeByteArray(byte [] buffer, final int boffset,
      final byte [] row, final int roffset, final int rlength,
      final byte [] family, final int foffset, int flength,
      final byte [] qualifier, final int qoffset, int qlength,
      final long timestamp, final Type type,
      final byte [] value, final int voffset, int vlength, Tag[] tags) {

    checkParameters(row, rlength, family, flength, qlength, vlength);

    // Calculate length of tags area
    int tagsLength = 0;
    if (tags != null && tags.length > 0) {
      for (Tag t: tags) {
        tagsLength += t.getLength();
      }
    }
    checkForTagsLength(tagsLength);
    int keyLength = (int) getKeyDataStructureSize(rlength, flength, qlength);
    int keyValueLength = (int) getKeyValueDataStructureSize(rlength, flength, qlength, vlength,
        tagsLength);
    if (keyValueLength > buffer.length - boffset) {
      throw new IllegalArgumentException("Buffer size " + (buffer.length - boffset) + " < " +
          keyValueLength);
    }

    // Write key, value and key row length.
    int pos = boffset;
    pos = Bytes.putInt(buffer, pos, keyLength);
    pos = Bytes.putInt(buffer, pos, vlength);
    pos = Bytes.putShort(buffer, pos, (short)(rlength & 0x0000ffff));
    pos = Bytes.putBytes(buffer, pos, row, roffset, rlength);
    pos = Bytes.putByte(buffer, pos, (byte) (flength & 0x0000ff));
    if (flength != 0) {
      pos = Bytes.putBytes(buffer, pos, family, foffset, flength);
    }
    if (qlength != 0) {
      pos = Bytes.putBytes(buffer, pos, qualifier, qoffset, qlength);
    }
    pos = Bytes.putLong(buffer, pos, timestamp);
    pos = Bytes.putByte(buffer, pos, type.getCode());
    if (value != null && value.length > 0) {
      pos = Bytes.putBytes(buffer, pos, value, voffset, vlength);
    }
    // Write the number of tags. If it is 0 then it means there are no tags.
    if (tagsLength > 0) {
      pos = Bytes.putAsShort(buffer, pos, tagsLength);
      for (Tag t : tags) {
        pos = Bytes.putBytes(buffer, pos, t.getBuffer(), t.getOffset(), t.getLength());
      }
    }
    return keyValueLength;
  }

  private static void checkForTagsLength(int tagsLength) {
    if (tagsLength > MAX_TAGS_LENGTH) {
      throw new IllegalArgumentException("tagslength "+ tagsLength + " > " + MAX_TAGS_LENGTH);
    }
  }

  /**
   * Write KeyValue format into a byte array.
   * @param row row key
   * @param roffset row offset
   * @param rlength row length
   * @param family family name
   * @param foffset family offset
   * @param flength family length
   * @param qualifier column qualifier
   * @param qoffset qualifier offset
   * @param qlength qualifier length
   * @param timestamp version timestamp
   * @param type key type
   * @param value column value
   * @param voffset value offset
   * @param vlength value length
   * @return The newly created byte array.
   */
  private static byte [] createByteArray(final byte [] row, final int roffset,
      final int rlength, final byte [] family, final int foffset, int flength,
      final byte [] qualifier, final int qoffset, int qlength,
      final long timestamp, final Type type,
      final byte [] value, final int voffset, 
      int vlength, byte[] tags, int tagsOffset, int tagsLength) {

    checkParameters(row, rlength, family, flength, qlength, vlength);
    checkForTagsLength(tagsLength);
    // Allocate right-sized byte array.
    int keyLength = (int) getKeyDataStructureSize(rlength, flength, qlength);
    byte [] bytes =
        new byte[(int) getKeyValueDataStructureSize(rlength, flength, qlength, vlength, tagsLength)];
    // Write key, value and key row length.
    int pos = 0;
    pos = Bytes.putInt(bytes, pos, keyLength);
    pos = Bytes.putInt(bytes, pos, vlength);
    pos = Bytes.putShort(bytes, pos, (short)(rlength & 0x0000ffff));
    pos = Bytes.putBytes(bytes, pos, row, roffset, rlength);
    pos = Bytes.putByte(bytes, pos, (byte)(flength & 0x0000ff));
    if(flength != 0) {
      pos = Bytes.putBytes(bytes, pos, family, foffset, flength);
    }
    if(qlength != 0) {
      pos = Bytes.putBytes(bytes, pos, qualifier, qoffset, qlength);
    }
    pos = Bytes.putLong(bytes, pos, timestamp);
    pos = Bytes.putByte(bytes, pos, type.getCode());
    if (value != null && value.length > 0) {
      pos = Bytes.putBytes(bytes, pos, value, voffset, vlength);
    }
    // Add the tags after the value part
    if (tagsLength > 0) {
      pos = Bytes.putAsShort(bytes, pos, tagsLength);
      pos = Bytes.putBytes(bytes, pos, tags, tagsOffset, tagsLength);
    }
    return bytes;
  }

  /**
   * @param qualifier can be a ByteBuffer or a byte[], or null.
   * @param value can be a ByteBuffer or a byte[], or null.
   */
  private static byte [] createByteArray(final byte [] row, final int roffset,
      final int rlength, final byte [] family, final int foffset, int flength,
      final Object qualifier, final int qoffset, int qlength,
      final long timestamp, final Type type,
      final Object value, final int voffset, int vlength, List<Tag> tags) {

    checkParameters(row, rlength, family, flength, qlength, vlength);

    // Calculate length of tags area
    int tagsLength = 0;
    if (tags != null && !tags.isEmpty()) {
      for (Tag t : tags) {
        tagsLength += t.getLength();
      }
    }
    checkForTagsLength(tagsLength);
    // Allocate right-sized byte array.
    int keyLength = (int) getKeyDataStructureSize(rlength, flength, qlength);
    byte[] bytes = new byte[(int) getKeyValueDataStructureSize(rlength, flength, qlength, vlength,
        tagsLength)];

    // Write key, value and key row length.
    int pos = 0;
    pos = Bytes.putInt(bytes, pos, keyLength);

    pos = Bytes.putInt(bytes, pos, vlength);
    pos = Bytes.putShort(bytes, pos, (short)(rlength & 0x0000ffff));
    pos = Bytes.putBytes(bytes, pos, row, roffset, rlength);
    pos = Bytes.putByte(bytes, pos, (byte)(flength & 0x0000ff));
    if(flength != 0) {
      pos = Bytes.putBytes(bytes, pos, family, foffset, flength);
    }
    if (qlength > 0) {
      if (qualifier instanceof ByteBuffer) {
        pos = Bytes.putByteBuffer(bytes, pos, (ByteBuffer) qualifier);
      } else {
        pos = Bytes.putBytes(bytes, pos, (byte[]) qualifier, qoffset, qlength);
      }
    }
    pos = Bytes.putLong(bytes, pos, timestamp);
    pos = Bytes.putByte(bytes, pos, type.getCode());
    if (vlength > 0) {
      if (value instanceof ByteBuffer) {
        pos = Bytes.putByteBuffer(bytes, pos, (ByteBuffer) value);
      } else {
        pos = Bytes.putBytes(bytes, pos, (byte[]) value, voffset, vlength);
      }
    }
    // Add the tags after the value part
    if (tagsLength > 0) {
      pos = Bytes.putAsShort(bytes, pos, tagsLength);
      for (Tag t : tags) {
        pos = Bytes.putBytes(bytes, pos, t.getBuffer(), t.getOffset(), t.getLength());
      }
    }
    return bytes;
  }

  /**
   * Needed doing 'contains' on List.  Only compares the key portion, not the value.
   */
  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Cell)) {
      return false;
    }
    return CellComparator.equals(this, (Cell)other);
  }

  @Override
  public int hashCode() {
    byte[] b = getBuffer();
    int start = getOffset(), end = getOffset() + getLength();
    int h = b[start++];
    for (int i = start; i < end; i++) {
      h = (h * 13) ^ b[i];
    }
    return h;
  }

  //---------------------------------------------------------------------------
  //
  //  KeyValue cloning
  //
  //---------------------------------------------------------------------------

  /**
   * Clones a KeyValue.  This creates a copy, re-allocating the buffer.
   * @return Fully copied clone of this KeyValue
   * @throws CloneNotSupportedException
   */
  @Override
  public KeyValue clone() throws CloneNotSupportedException {
    super.clone();
    byte [] b = new byte[this.length];
    System.arraycopy(this.bytes, this.offset, b, 0, this.length);
    KeyValue ret = new KeyValue(b, 0, b.length);
    // Important to clone the memstoreTS as well - otherwise memstore's
    // update-in-place methods (eg increment) will end up creating
    // new entries
    ret.setSequenceId(seqId);
    return ret;
  }

  /**
   * Creates a shallow copy of this KeyValue, reusing the data byte buffer.
   * http://en.wikipedia.org/wiki/Object_copy
   * @return Shallow copy of this KeyValue
   */
  public KeyValue shallowCopy() {
    KeyValue shallowCopy = new KeyValue(this.bytes, this.offset, this.length);
    shallowCopy.setSequenceId(this.seqId);
    return shallowCopy;
  }

  //---------------------------------------------------------------------------
  //
  //  String representation
  //
  //---------------------------------------------------------------------------

  public String toString() {
    if (this.bytes == null || this.bytes.length == 0) {
      return "empty";
    }
    return keyToString(this.bytes, this.offset + ROW_OFFSET, getKeyLength()) + "/vlen="
      + getValueLength() + "/seqid=" + seqId;
  }

  /**
   * @param k Key portion of a KeyValue.
   * @return Key as a String, empty string if k is null. 
   */
  public static String keyToString(final byte [] k) {
    if (k == null) { 
      return "";
    }
    return keyToString(k, 0, k.length);
  }

  /**
   * Produces a string map for this key/value pair. Useful for programmatic use
   * and manipulation of the data stored in an HLogKey, for example, printing
   * as JSON. Values are left out due to their tendency to be large. If needed,
   * they can be added manually.
   *
   * @return the Map<String,?> containing data from this key
   */
  public Map<String, Object> toStringMap() {
    Map<String, Object> stringMap = new HashMap<String, Object>();
    stringMap.put("row", Bytes.toStringBinary(getRow()));
    stringMap.put("family", Bytes.toStringBinary(getFamily()));
    stringMap.put("qualifier", Bytes.toStringBinary(getQualifier()));
    stringMap.put("timestamp", getTimestamp());
    stringMap.put("vlen", getValueLength());
    List<Tag> tags = getTags();
    if (tags != null) {
      List<String> tagsString = new ArrayList<String>();
      for (Tag t : tags) {
        tagsString.add((t.getType()) + ":" +Bytes.toStringBinary(t.getValue()));
      }
      stringMap.put("tag", tagsString);
    }
    return stringMap;
  }

  /**
   * Use for logging.
   * @param b Key portion of a KeyValue.
   * @param o Offset to start of key
   * @param l Length of key.
   * @return Key as a String.
   */
  public static String keyToString(final byte [] b, final int o, final int l) {
    if (b == null) return "";
    int rowlength = Bytes.toShort(b, o);
    String row = Bytes.toStringBinary(b, o + Bytes.SIZEOF_SHORT, rowlength);
    int columnoffset = o + Bytes.SIZEOF_SHORT + 1 + rowlength;
    int familylength = b[columnoffset - 1];
    int columnlength = l - ((columnoffset - o) + TIMESTAMP_TYPE_SIZE);
    String family = familylength == 0? "":
      Bytes.toStringBinary(b, columnoffset, familylength);
    String qualifier = columnlength == 0? "":
      Bytes.toStringBinary(b, columnoffset + familylength,
      columnlength - familylength);
    long timestamp = Bytes.toLong(b, o + (l - TIMESTAMP_TYPE_SIZE));
    String timestampStr = humanReadableTimestamp(timestamp);
    byte type = b[o + l - 1];
    return row + "/" + family +
      (family != null && family.length() > 0? ":" :"") +
      qualifier + "/" + timestampStr + "/" + Type.codeToType(type);
  }

  public static String humanReadableTimestamp(final long timestamp) {
    if (timestamp == HConstants.LATEST_TIMESTAMP) {
      return "LATEST_TIMESTAMP";
    }
    if (timestamp == HConstants.OLDEST_TIMESTAMP) {
      return "OLDEST_TIMESTAMP";
    }
    return String.valueOf(timestamp);
  }

  //---------------------------------------------------------------------------
  //
  //  Public Member Accessors
  //
  //---------------------------------------------------------------------------

  /**
   * @return The byte array backing this KeyValue.
   * @deprecated Since 0.98.0.  Use Cell Interface instead.  Do not presume single backing buffer.
   */
  @Deprecated
  public byte [] getBuffer() {
    return this.bytes;
  }

  /**
   * @return Offset into {@link #getBuffer()} at which this KeyValue starts.
   */
  public int getOffset() {
    return this.offset;
  }

  /**
   * @return Length of bytes this KeyValue occupies in {@link #getBuffer()}.
   */
  public int getLength() {
    return length;
  }

  //---------------------------------------------------------------------------
  //
  //  Length and Offset Calculators
  //
  //---------------------------------------------------------------------------

  /**
   * Determines the total length of the KeyValue stored in the specified
   * byte array and offset.  Includes all headers.
   * @param bytes byte array
   * @param offset offset to start of the KeyValue
   * @return length of entire KeyValue, in bytes
   */
  private static int getLength(byte [] bytes, int offset) {
    int klength = ROW_OFFSET + Bytes.toInt(bytes, offset);
    int vlength = Bytes.toInt(bytes, offset + Bytes.SIZEOF_INT);
    return klength + vlength;
  }

  /**
   * @return Key offset in backing buffer..
   */
  public int getKeyOffset() {
    return this.offset + ROW_OFFSET;
  }

  public String getKeyString() {
    return Bytes.toStringBinary(getBuffer(), getKeyOffset(), getKeyLength());
  }

  /**
   * @return Length of key portion.
   */
  public int getKeyLength() {
    return Bytes.toInt(this.bytes, this.offset);
  }

  /**
   * @return the backing array of the entire KeyValue (all KeyValue fields are in a single array)
   */
  @Override
  public byte[] getValueArray() {
    return bytes;
  }

  /**
   * @return the value offset
   */
  @Override
  public int getValueOffset() {
    int voffset = getKeyOffset() + getKeyLength();
    return voffset;
  }

  /**
   * @return Value length
   */
  @Override
  public int getValueLength() {
    int vlength = Bytes.toInt(this.bytes, this.offset + Bytes.SIZEOF_INT);
    return vlength;
  }

  /**
   * @return the backing array of the entire KeyValue (all KeyValue fields are in a single array)
   */
  @Override
  public byte[] getRowArray() {
    return bytes;
  }

  /**
   * @return Row offset
   */
  @Override
  public int getRowOffset() {
    return getKeyOffset() + Bytes.SIZEOF_SHORT;
  }

  /**
   * @return Row length
   */
  @Override
  public short getRowLength() {
    return Bytes.toShort(this.bytes, getKeyOffset());
  }

  /**
   * @return the backing array of the entire KeyValue (all KeyValue fields are in a single array)
   */
  @Override
  public byte[] getFamilyArray() {
    return bytes;
  }

  /**
   * @return Family offset
   */
  @Override
  public int getFamilyOffset() {
    return getFamilyOffset(getRowLength());
  }

  /**
   * @return Family offset
   */
  private int getFamilyOffset(int rlength) {
    return this.offset + ROW_OFFSET + Bytes.SIZEOF_SHORT + rlength + Bytes.SIZEOF_BYTE;
  }

  /**
   * @return Family length
   */
  @Override
  public byte getFamilyLength() {
    return getFamilyLength(getFamilyOffset());
  }

  /**
   * @return Family length
   */
  public byte getFamilyLength(int foffset) {
    return this.bytes[foffset-1];
  }

  /**
   * @return the backing array of the entire KeyValue (all KeyValue fields are in a single array)
   */
  @Override
  public byte[] getQualifierArray() {
    return bytes;
  }

  /**
   * @return Qualifier offset
   */
  @Override
  public int getQualifierOffset() {
    return getQualifierOffset(getFamilyOffset());
  }

  /**
   * @return Qualifier offset
   */
  private int getQualifierOffset(int foffset) {
    return foffset + getFamilyLength(foffset);
  }

  /**
   * @return Qualifier length
   */
  @Override
  public int getQualifierLength() {
    return getQualifierLength(getRowLength(),getFamilyLength());
  }

  /**
   * @return Qualifier length
   */
  private int getQualifierLength(int rlength, int flength) {
    return getKeyLength() - (int) getKeyDataStructureSize(rlength, flength, 0);
  }

  /**
   * @return Timestamp offset
   */
  public int getTimestampOffset() {
    return getTimestampOffset(getKeyLength());
  }

  /**
   * @param keylength Pass if you have it to save on a int creation.
   * @return Timestamp offset
   */
  private int getTimestampOffset(final int keylength) {
    return getKeyOffset() + keylength - TIMESTAMP_TYPE_SIZE;
  }

  /**
   * @return True if this KeyValue has a LATEST_TIMESTAMP timestamp.
   */
  public boolean isLatestTimestamp() {
    return Bytes.equals(getBuffer(), getTimestampOffset(), Bytes.SIZEOF_LONG,
      HConstants.LATEST_TIMESTAMP_BYTES, 0, Bytes.SIZEOF_LONG);
  }

  /**
   * @param now Time to set into <code>this</code> IFF timestamp ==
   * {@link HConstants#LATEST_TIMESTAMP} (else, its a noop).
   * @return True is we modified this.
   */
  public boolean updateLatestStamp(final byte [] now) {
    if (this.isLatestTimestamp()) {
      int tsOffset = getTimestampOffset();
      System.arraycopy(now, 0, this.bytes, tsOffset, Bytes.SIZEOF_LONG);
      // clear cache or else getTimestamp() possibly returns an old value
      return true;
    }
    return false;
  }

  @Override
  public void setTimestamp(long ts) {
    Bytes.putBytes(this.bytes, this.getTimestampOffset(), Bytes.toBytes(ts), 0, Bytes.SIZEOF_LONG);
  }

  @Override
  public void setTimestamp(byte[] ts, int tsOffset) {
    Bytes.putBytes(this.bytes, this.getTimestampOffset(), ts, tsOffset, Bytes.SIZEOF_LONG);
  }

  //---------------------------------------------------------------------------
  //
  //  Methods that return copies of fields
  //
  //---------------------------------------------------------------------------

  /**
   * Do not use unless you have to.  Used internally for compacting and testing.
   *
   * Use {@link #getRow()}, {@link #getFamily()}, {@link #getQualifier()}, and
   * {@link #getValue()} if accessing a KeyValue client-side.
   * @return Copy of the key portion only.
   */
  public byte [] getKey() {
    int keylength = getKeyLength();
    byte [] key = new byte[keylength];
    System.arraycopy(getBuffer(), getKeyOffset(), key, 0, keylength);
    return key;
  }

  /**
   * Returns value in a new byte array.
   * Primarily for use client-side. If server-side, use
   * {@link #getBuffer()} with appropriate offsets and lengths instead to
   * save on allocations.
   * @return Value in a new byte array.
   */
  @Deprecated // use CellUtil.getValueArray()
  public byte [] getValue() {
    return CellUtil.cloneValue(this);
  }

  /**
   * Primarily for use client-side.  Returns the row of this KeyValue in a new
   * byte array.<p>
   *
   * If server-side, use {@link #getBuffer()} with appropriate offsets and
   * lengths instead.
   * @return Row in a new byte array.
   */
  @Deprecated // use CellUtil.getRowArray()
  public byte [] getRow() {
    return CellUtil.cloneRow(this);
  }

  /**
   *
   * @return Timestamp
   */
  @Override
  public long getTimestamp() {
    return getTimestamp(getKeyLength());
  }

  /**
   * @param keylength Pass if you have it to save on a int creation.
   * @return Timestamp
   */
  long getTimestamp(final int keylength) {
    int tsOffset = getTimestampOffset(keylength);
    return Bytes.toLong(this.bytes, tsOffset);
  }

  /**
   * @return Type of this KeyValue.
   */
  @Deprecated
  public byte getType() {
    return getTypeByte();
  }

  /**
   * @return KeyValue.TYPE byte representation
   */
  @Override
  public byte getTypeByte() {
    return this.bytes[this.offset + getKeyLength() - 1 + ROW_OFFSET];
  }

  /**
   * @return True if a delete type, a {@link KeyValue.Type#Delete} or
   * a {KeyValue.Type#DeleteFamily} or a {@link KeyValue.Type#DeleteColumn}
   * KeyValue type.
   */
  @Deprecated // use CellUtil#isDelete
  public boolean isDelete() {
    return KeyValue.isDelete(getType());
  }

  /**
   * Primarily for use client-side.  Returns the family of this KeyValue in a
   * new byte array.<p>
   *
   * If server-side, use {@link #getBuffer()} with appropriate offsets and
   * lengths instead.
   * @return Returns family. Makes a copy.
   */
  @Deprecated // use CellUtil.getFamilyArray
  public byte [] getFamily() {
    return CellUtil.cloneFamily(this);
  }

  /**
   * Primarily for use client-side.  Returns the column qualifier of this
   * KeyValue in a new byte array.<p>
   *
   * If server-side, use {@link #getBuffer()} with appropriate offsets and
   * lengths instead.
   * Use {@link #getBuffer()} with appropriate offsets and lengths instead.
   * @return Returns qualifier. Makes a copy.
   */
  @Deprecated // use CellUtil.getQualifierArray
  public byte [] getQualifier() {
    return CellUtil.cloneQualifier(this);
  }

  /**
   * This returns the offset where the tag actually starts.
   */
  @Override
  public int getTagsOffset() {
    int tagsLen = getTagsLength();
    if (tagsLen == 0) {
      return this.offset + this.length;
    }
    return this.offset + this.length - tagsLen;
  }

  /**
   * This returns the total length of the tag bytes
   */
  @Override
  public int getTagsLength() {
    int tagsLen = this.length - (getKeyLength() + getValueLength() + KEYVALUE_INFRASTRUCTURE_SIZE);
    if (tagsLen > 0) {
      // There are some Tag bytes in the byte[]. So reduce 2 bytes which is added to denote the tags
      // length
      tagsLen -= TAGS_LENGTH_SIZE;
    }
    return tagsLen;
  }

  /**
   * Returns any tags embedded in the KeyValue.  Used in testcases.
   * @return The tags
   */
  public List<Tag> getTags() {
    int tagsLength = getTagsLength();
    if (tagsLength == 0) {
      return EMPTY_ARRAY_LIST;
    }
    return Tag.asList(getTagsArray(), getTagsOffset(), tagsLength);
  }

  /**
   * @return the backing array of the entire KeyValue (all KeyValue fields are in a single array)
   */
  @Override
  public byte[] getTagsArray() {
    return bytes;
  }

  /**
   * Creates a new KeyValue that only contains the key portion (the value is
   * set to be null).
   *
   * TODO only used by KeyOnlyFilter -- move there.
   * @param lenAsVal replace value with the actual value length (false=empty)
   */
  public KeyValue createKeyOnly(boolean lenAsVal) {
    // KV format:  <keylen:4><valuelen:4><key:keylen><value:valuelen>
    // Rebuild as: <keylen:4><0:4><key:keylen>
    int dataLen = lenAsVal? Bytes.SIZEOF_INT : 0;
    byte [] newBuffer = new byte[getKeyLength() + ROW_OFFSET + dataLen];
    System.arraycopy(this.bytes, this.offset, newBuffer, 0,
        Math.min(newBuffer.length,this.length));
    Bytes.putInt(newBuffer, Bytes.SIZEOF_INT, dataLen);
    if (lenAsVal) {
      Bytes.putInt(newBuffer, newBuffer.length - dataLen, this.getValueLength());
    }
    return new KeyValue(newBuffer);
  }

  /**
   * Splits a column in {@code family:qualifier} form into separate byte arrays. An empty qualifier
   * (ie, {@code fam:}) is parsed as <code>{ fam, EMPTY_BYTE_ARRAY }</code> while no delimiter (ie,
   * {@code fam}) is parsed as an array of one element, <code>{ fam }</code>.
   * <p>
   * Don't forget, HBase DOES support empty qualifiers. (see HBASE-9549)
   * </p>
   * <p>
   * Not recommend to be used as this is old-style API.
   * </p>
   * @param c The column.
   * @return The parsed column.
   */
  public static byte [][] parseColumn(byte [] c) {
    final int index = getDelimiter(c, 0, c.length, COLUMN_FAMILY_DELIMITER);
    if (index == -1) {
      // If no delimiter, return array of size 1
      return new byte [][] { c };
    } else if(index == c.length - 1) {
      // family with empty qualifier, return array size 2
      byte [] family = new byte[c.length-1];
      System.arraycopy(c, 0, family, 0, family.length);
      return new byte [][] { family, HConstants.EMPTY_BYTE_ARRAY};
    }
    // Family and column, return array size 2
    final byte [][] result = new byte [2][];
    result[0] = new byte [index];
    System.arraycopy(c, 0, result[0], 0, index);
    final int len = c.length - (index + 1);
    result[1] = new byte[len];
    System.arraycopy(c, index + 1 /* Skip delimiter */, result[1], 0, len);
    return result;
  }

  /**
   * Makes a column in family:qualifier form from separate byte arrays.
   * <p>
   * Not recommended for usage as this is old-style API.
   * @param family
   * @param qualifier
   * @return family:qualifier
   */
  public static byte [] makeColumn(byte [] family, byte [] qualifier) {
    return Bytes.add(family, COLUMN_FAMILY_DELIM_ARRAY, qualifier);
  }

  /**
   * @param b
   * @param delimiter
   * @return Index of delimiter having started from start of <code>b</code>
   * moving rightward.
   */
  public static int getDelimiter(final byte [] b, int offset, final int length,
      final int delimiter) {
    if (b == null) {
      throw new IllegalArgumentException("Passed buffer is null");
    }
    int result = -1;
    for (int i = offset; i < length + offset; i++) {
      if (b[i] == delimiter) {
        result = i;
        break;
      }
    }
    return result;
  }

  /**
   * Find index of passed delimiter walking from end of buffer backwards.
   * @param b
   * @param delimiter
   * @return Index of delimiter
   */
  public static int getDelimiterInReverse(final byte [] b, final int offset,
      final int length, final int delimiter) {
    if (b == null) {
      throw new IllegalArgumentException("Passed buffer is null");
    }
    int result = -1;
    for (int i = (offset + length) - 1; i >= offset; i--) {
      if (b[i] == delimiter) {
        result = i;
        break;
      }
    }
    return result;
  }

  /**
   * A {@link KVComparator} for <code>hbase:meta</code> catalog table
   * {@link KeyValue}s.
   */
  public static class MetaComparator extends KVComparator {
    /**
     * Compare key portion of a {@link KeyValue} for keys in <code>hbase:meta</code>
     * table.
     */
    @Override
    public int compare(final Cell left, final Cell right) {
      int c = compareRowKey(left, right);
      if (c != 0) {
        return c;
      }
      return CellComparator.compareWithoutRow(left, right);
    }

    @Override
    public int compareOnlyKeyPortion(Cell left, Cell right) {
      return compare(left, right);
    }

    @Override
    public int compareRows(byte [] left, int loffset, int llength,
        byte [] right, int roffset, int rlength) {
      int leftDelimiter = getDelimiter(left, loffset, llength,
          HConstants.DELIMITER);
      int rightDelimiter = getDelimiter(right, roffset, rlength,
          HConstants.DELIMITER);
      // Compare up to the delimiter
      int lpart = (leftDelimiter < 0 ? llength :leftDelimiter - loffset);
      int rpart = (rightDelimiter < 0 ? rlength :rightDelimiter - roffset);
      int result = Bytes.compareTo(left, loffset, lpart, right, roffset, rpart);
      if (result != 0) {
        return result;
      } else {
        if (leftDelimiter < 0 && rightDelimiter >= 0) {
          return -1;
        } else if (rightDelimiter < 0 && leftDelimiter >= 0) {
          return 1;
        } else if (leftDelimiter < 0 && rightDelimiter < 0) {
          return 0;
        }
      }
      // Compare middle bit of the row.
      // Move past delimiter
      leftDelimiter++;
      rightDelimiter++;
      int leftFarDelimiter = getDelimiterInReverse(left, leftDelimiter,
          llength - (leftDelimiter - loffset), HConstants.DELIMITER);
      int rightFarDelimiter = getDelimiterInReverse(right,
          rightDelimiter, rlength - (rightDelimiter - roffset),
          HConstants.DELIMITER);
      // Now compare middlesection of row.
      lpart = (leftFarDelimiter < 0 ? llength + loffset: leftFarDelimiter) - leftDelimiter;
      rpart = (rightFarDelimiter < 0 ? rlength + roffset: rightFarDelimiter)- rightDelimiter;
      result = super.compareRows(left, leftDelimiter, lpart, right, rightDelimiter, rpart);
      if (result != 0) {
        return result;
      }  else {
        if (leftDelimiter < 0 && rightDelimiter >= 0) {
          return -1;
        } else if (rightDelimiter < 0 && leftDelimiter >= 0) {
          return 1;
        } else if (leftDelimiter < 0 && rightDelimiter < 0) {
          return 0;
        }
      }
      // Compare last part of row, the rowid.
      leftFarDelimiter++;
      rightFarDelimiter++;
      result = Bytes.compareTo(left, leftFarDelimiter, llength - (leftFarDelimiter - loffset),
          right, rightFarDelimiter, rlength - (rightFarDelimiter - roffset));
      return result;
    }

    /**
     * Don't do any fancy Block Index splitting tricks.
     */
    @Override
    public byte[] getShortMidpointKey(final byte[] leftKey, final byte[] rightKey) {
      return Arrays.copyOf(rightKey, rightKey.length);
    }

    /**
     * The HFileV2 file format's trailer contains this class name.  We reinterpret this and
     * instantiate the appropriate comparator.
     * TODO: With V3 consider removing this.
     * @return legacy class name for FileFileTrailer#comparatorClassName
     */
    @Override
    public String getLegacyKeyComparatorName() {
      return "org.apache.hadoop.hbase.KeyValue$MetaKeyComparator";
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
      return new MetaComparator();
    }

    /**
     * Override the row key comparison to parse and compare the meta row key parts.
     */
    @Override
    protected int compareRowKey(final Cell l, final Cell r) {
      byte[] left = l.getRowArray();
      int loffset = l.getRowOffset();
      int llength = l.getRowLength();
      byte[] right = r.getRowArray();
      int roffset = r.getRowOffset();
      int rlength = r.getRowLength();
      return compareRows(left, loffset, llength, right, roffset, rlength);
    }
  }

  /**
   * Compare KeyValues.  When we compare KeyValues, we only compare the Key
   * portion.  This means two KeyValues with same Key but different Values are
   * considered the same as far as this Comparator is concerned.
   */
  public static class KVComparator implements RawComparator<Cell>, SamePrefixComparator<byte[]> {

    /**
     * The HFileV2 file format's trailer contains this class name.  We reinterpret this and
     * instantiate the appropriate comparator.
     * TODO: With V3 consider removing this.
     * @return legacy class name for FileFileTrailer#comparatorClassName
     */
    public String getLegacyKeyComparatorName() {
      return "org.apache.hadoop.hbase.KeyValue$KeyComparator";
    }

    @Override // RawComparator
    public int compare(byte[] l, int loff, int llen, byte[] r, int roff, int rlen) {
      return compareFlatKey(l,loff,llen, r,roff,rlen);
    }

    
    /**
     * Compares the only the user specified portion of a Key.  This is overridden by MetaComparator.
     * @param left
     * @param right
     * @return 0 if equal, <0 if left smaller, >0 if right smaller
     */
    protected int compareRowKey(final Cell left, final Cell right) {
      return CellComparator.compareRows(left, right);
    }

    /**
     * Compares left to right assuming that left,loffset,llength and right,roffset,rlength are
     * full KVs laid out in a flat byte[]s.
     * @param left
     * @param loffset
     * @param llength
     * @param right
     * @param roffset
     * @param rlength
     * @return  0 if equal, <0 if left smaller, >0 if right smaller
     */
    public int compareFlatKey(byte[] left, int loffset, int llength,
        byte[] right, int roffset, int rlength) {
      // Compare row
      short lrowlength = Bytes.toShort(left, loffset);
      short rrowlength = Bytes.toShort(right, roffset);
      int compare = compareRows(left, loffset + Bytes.SIZEOF_SHORT,
          lrowlength, right, roffset + Bytes.SIZEOF_SHORT, rrowlength);
      if (compare != 0) {
        return compare;
      }

      // Compare the rest of the two KVs without making any assumptions about
      // the common prefix. This function will not compare rows anyway, so we
      // don't need to tell it that the common prefix includes the row.
      return compareWithoutRow(0, left, loffset, llength, right, roffset,
          rlength, rrowlength);
    }

    public int compareFlatKey(byte[] left, byte[] right) {
      return compareFlatKey(left, 0, left.length, right, 0, right.length);
    }

    public int compareOnlyKeyPortion(Cell left, Cell right) {
      return CellComparator.compare(left, right, true);
    }

    /**
     * Compares the Key of a cell -- with fields being more significant in this order:
     * rowkey, colfam/qual, timestamp, type, mvcc
     */
    @Override
    public int compare(final Cell left, final Cell right) {
      int compare = CellComparator.compare(left, right, false);
      return compare;
    }

    public int compareTimestamps(final Cell left, final Cell right) {
      return CellComparator.compareTimestamps(left, right);
    }

    /**
     * @param left
     * @param right
     * @return Result comparing rows.
     */
    public int compareRows(final Cell left, final Cell right) {
      return compareRows(left.getRowArray(),left.getRowOffset(), left.getRowLength(),
      right.getRowArray(), right.getRowOffset(), right.getRowLength());
    }

    /**
     * Get the b[],o,l for left and right rowkey portions and compare.
     * @param left
     * @param loffset
     * @param llength
     * @param right
     * @param roffset
     * @param rlength
     * @return 0 if equal, <0 if left smaller, >0 if right smaller
     */
    public int compareRows(byte [] left, int loffset, int llength,
        byte [] right, int roffset, int rlength) {
      return Bytes.compareTo(left, loffset, llength, right, roffset, rlength);
    }

    int compareColumns(final Cell left, final short lrowlength, final Cell right,
        final short rrowlength) {
      return CellComparator.compareColumns(left, right);
    }

    protected int compareColumns(
        byte [] left, int loffset, int llength, final int lfamilylength,
        byte [] right, int roffset, int rlength, final int rfamilylength) {
      // Compare family portion first.
      int diff = Bytes.compareTo(left, loffset, lfamilylength,
        right, roffset, rfamilylength);
      if (diff != 0) {
        return diff;
      }
      // Compare qualifier portion
      return Bytes.compareTo(left, loffset + lfamilylength,
        llength - lfamilylength,
        right, roffset + rfamilylength, rlength - rfamilylength);
      }

    static int compareTimestamps(final long ltimestamp, final long rtimestamp) {
      // The below older timestamps sorting ahead of newer timestamps looks
      // wrong but it is intentional. This way, newer timestamps are first
      // found when we iterate over a memstore and newer versions are the
      // first we trip over when reading from a store file.
      if (ltimestamp < rtimestamp) {
        return 1;
      } else if (ltimestamp > rtimestamp) {
        return -1;
      }
      return 0;
    }

    /**
     * Overridden
     * @param commonPrefix
     * @param left
     * @param loffset
     * @param llength
     * @param right
     * @param roffset
     * @param rlength
     * @return 0 if equal, <0 if left smaller, >0 if right smaller
     */
    @Override // SamePrefixComparator
    public int compareIgnoringPrefix(int commonPrefix, byte[] left,
        int loffset, int llength, byte[] right, int roffset, int rlength) {
      // Compare row
      short lrowlength = Bytes.toShort(left, loffset);
      short rrowlength;

      int comparisonResult = 0;
      if (commonPrefix < ROW_LENGTH_SIZE) {
        // almost nothing in common
        rrowlength = Bytes.toShort(right, roffset);
        comparisonResult = compareRows(left, loffset + ROW_LENGTH_SIZE,
            lrowlength, right, roffset + ROW_LENGTH_SIZE, rrowlength);
      } else { // the row length is the same
        rrowlength = lrowlength;
        if (commonPrefix < ROW_LENGTH_SIZE + rrowlength) {
          // The rows are not the same. Exclude the common prefix and compare
          // the rest of the two rows.
          int common = commonPrefix - ROW_LENGTH_SIZE;
          comparisonResult = compareRows(
              left, loffset + common + ROW_LENGTH_SIZE, lrowlength - common,
              right, roffset + common + ROW_LENGTH_SIZE, rrowlength - common);
        }
      }
      if (comparisonResult != 0) {
        return comparisonResult;
      }

      assert lrowlength == rrowlength;
      return compareWithoutRow(commonPrefix, left, loffset, llength, right,
          roffset, rlength, lrowlength);
    }

    /**
     * Compare columnFamily, qualifier, timestamp, and key type (everything
     * except the row). This method is used both in the normal comparator and
     * the "same-prefix" comparator. Note that we are assuming that row portions
     * of both KVs have already been parsed and found identical, and we don't
     * validate that assumption here.
     * @param commonPrefix
     *          the length of the common prefix of the two key-values being
     *          compared, including row length and row
     */
    private int compareWithoutRow(int commonPrefix, byte[] left, int loffset,
        int llength, byte[] right, int roffset, int rlength, short rowlength) {
      /***
       * KeyValue Format and commonLength:
       * |_keyLen_|_valLen_|_rowLen_|_rowKey_|_famiLen_|_fami_|_Quali_|....
       * ------------------|-------commonLength--------|--------------
       */
      int commonLength = ROW_LENGTH_SIZE + FAMILY_LENGTH_SIZE + rowlength;

      // commonLength + TIMESTAMP_TYPE_SIZE
      int commonLengthWithTSAndType = TIMESTAMP_TYPE_SIZE + commonLength;
      // ColumnFamily + Qualifier length.
      int lcolumnlength = llength - commonLengthWithTSAndType;
      int rcolumnlength = rlength - commonLengthWithTSAndType;

      byte ltype = left[loffset + (llength - 1)];
      byte rtype = right[roffset + (rlength - 1)];

      // If the column is not specified, the "minimum" key type appears the
      // latest in the sorted order, regardless of the timestamp. This is used
      // for specifying the last key/value in a given row, because there is no
      // "lexicographically last column" (it would be infinitely long). The
      // "maximum" key type does not need this behavior.
      if (lcolumnlength == 0 && ltype == Type.Minimum.getCode()) {
        // left is "bigger", i.e. it appears later in the sorted order
        return 1;
      }
      if (rcolumnlength == 0 && rtype == Type.Minimum.getCode()) {
        return -1;
      }

      int lfamilyoffset = commonLength + loffset;
      int rfamilyoffset = commonLength + roffset;

      // Column family length.
      int lfamilylength = left[lfamilyoffset - 1];
      int rfamilylength = right[rfamilyoffset - 1];
      // If left family size is not equal to right family size, we need not
      // compare the qualifiers.
      boolean sameFamilySize = (lfamilylength == rfamilylength);
      int common = 0;
      if (commonPrefix > 0) {
        common = Math.max(0, commonPrefix - commonLength);
        if (!sameFamilySize) {
          // Common should not be larger than Math.min(lfamilylength,
          // rfamilylength).
          common = Math.min(common, Math.min(lfamilylength, rfamilylength));
        } else {
          common = Math.min(common, Math.min(lcolumnlength, rcolumnlength));
        }
      }
      if (!sameFamilySize) {
        // comparing column family is enough.
        return Bytes.compareTo(left, lfamilyoffset + common, lfamilylength
            - common, right, rfamilyoffset + common, rfamilylength - common);
      }
      // Compare family & qualifier together.
      final int comparison = Bytes.compareTo(left, lfamilyoffset + common,
          lcolumnlength - common, right, rfamilyoffset + common,
          rcolumnlength - common);
      if (comparison != 0) {
        return comparison;
      }

      ////
      // Next compare timestamps.
      long ltimestamp = Bytes.toLong(left,
          loffset + (llength - TIMESTAMP_TYPE_SIZE));
      long rtimestamp = Bytes.toLong(right,
          roffset + (rlength - TIMESTAMP_TYPE_SIZE));
      int compare = compareTimestamps(ltimestamp, rtimestamp);
      if (compare != 0) {
        return compare;
      }

      // Compare types. Let the delete types sort ahead of puts; i.e. types
      // of higher numbers sort before those of lesser numbers. Maximum (255)
      // appears ahead of everything, and minimum (0) appears after
      // everything.
      return (0xff & rtype) - (0xff & ltype);
    }

    protected int compareFamilies(final byte[] left, final int loffset, final int lfamilylength,
        final byte[] right, final int roffset, final int rfamilylength) {
      int diff = Bytes.compareTo(left, loffset, lfamilylength, right, roffset, rfamilylength);
      return diff;
    }

    protected int compareColumns(final byte[] left, final int loffset, final int lquallength,
        final byte[] right, final int roffset, final int rquallength) {
      int diff = Bytes.compareTo(left, loffset, lquallength, right, roffset, rquallength);
      return diff;
    }
    /**
     * Compares the row and column of two keyvalues for equality
     * @param left
     * @param right
     * @return True if same row and column.
     */
    public boolean matchingRowColumn(final Cell left,
        final Cell right) {
      short lrowlength = left.getRowLength();
      short rrowlength = right.getRowLength();

      // TsOffset = end of column data. just comparing Row+CF length of each
      if ((left.getRowLength() + left.getFamilyLength() + left.getQualifierLength()) != (right
          .getRowLength() + right.getFamilyLength() + right.getQualifierLength())) {
        return false;
      }

      if (!matchingRows(left, lrowlength, right, rrowlength)) {
        return false;
      }

      int lfoffset = left.getFamilyOffset();
      int rfoffset = right.getFamilyOffset();
      int lclength = left.getQualifierLength();
      int rclength = right.getQualifierLength();
      int lfamilylength = left.getFamilyLength();
      int rfamilylength = right.getFamilyLength();
      int diff = compareFamilies(left.getFamilyArray(), lfoffset, lfamilylength,
          right.getFamilyArray(), rfoffset, rfamilylength);
      if (diff != 0) {
        return false;
      } else {
        diff = compareColumns(left.getQualifierArray(), left.getQualifierOffset(), lclength,
            right.getQualifierArray(), right.getQualifierOffset(), rclength);
        return diff == 0;
      }
    }

    /**
     * Compares the row of two keyvalues for equality
     * @param left
     * @param right
     * @return True if rows match.
     */
    public boolean matchingRows(final Cell left, final Cell right) {
      short lrowlength = left.getRowLength();
      short rrowlength = right.getRowLength();
      return matchingRows(left, lrowlength, right, rrowlength);
    }

    /**
     * @param left
     * @param lrowlength
     * @param right
     * @param rrowlength
     * @return True if rows match.
     */
    private boolean matchingRows(final Cell left, final short lrowlength,
        final Cell right, final short rrowlength) {
      return lrowlength == rrowlength &&
          matchingRows(left.getRowArray(), left.getRowOffset(), lrowlength,
              right.getRowArray(), right.getRowOffset(), rrowlength);
    }

    /**
     * Compare rows. Just calls Bytes.equals, but it's good to have this encapsulated.
     * @param left Left row array.
     * @param loffset Left row offset.
     * @param llength Left row length.
     * @param right Right row array.
     * @param roffset Right row offset.
     * @param rlength Right row length.
     * @return Whether rows are the same row.
     */
    public boolean matchingRows(final byte [] left, final int loffset, final int llength,
        final byte [] right, final int roffset, final int rlength) {
      return Bytes.equals(left, loffset, llength, right, roffset, rlength);
    }

    public byte[] calcIndexKey(byte[] lastKeyOfPreviousBlock, byte[] firstKeyInBlock) {
      byte[] fakeKey = getShortMidpointKey(lastKeyOfPreviousBlock, firstKeyInBlock);
      if (compareFlatKey(fakeKey, firstKeyInBlock) > 0) {
        LOG.error("Unexpected getShortMidpointKey result, fakeKey:"
            + Bytes.toStringBinary(fakeKey) + ", firstKeyInBlock:"
            + Bytes.toStringBinary(firstKeyInBlock));
        return firstKeyInBlock;
      }
      if (lastKeyOfPreviousBlock != null && compareFlatKey(lastKeyOfPreviousBlock, fakeKey) >= 0) {
        LOG.error("Unexpected getShortMidpointKey result, lastKeyOfPreviousBlock:" +
            Bytes.toStringBinary(lastKeyOfPreviousBlock) + ", fakeKey:" +
            Bytes.toStringBinary(fakeKey));
        return firstKeyInBlock;
      }
      return fakeKey;
    }

    /**
     * This is a HFile block index key optimization.
     * @param leftKey
     * @param rightKey
     * @return 0 if equal, <0 if left smaller, >0 if right smaller
     * @deprecated Since 0.99.2; Use {@link CellComparator#getMidpoint(Cell, Cell)} instead.
     */
    @Deprecated
    public byte[] getShortMidpointKey(final byte[] leftKey, final byte[] rightKey) {
      if (rightKey == null) {
        throw new IllegalArgumentException("rightKey can not be null");
      }
      if (leftKey == null) {
        return Arrays.copyOf(rightKey, rightKey.length);
      }
      if (compareFlatKey(leftKey, rightKey) >= 0) {
        throw new IllegalArgumentException("Unexpected input, leftKey:" + Bytes.toString(leftKey)
          + ", rightKey:" + Bytes.toString(rightKey));
      }

      short leftRowLength = Bytes.toShort(leftKey, 0);
      short rightRowLength = Bytes.toShort(rightKey, 0);
      int leftCommonLength = ROW_LENGTH_SIZE + FAMILY_LENGTH_SIZE + leftRowLength;
      int rightCommonLength = ROW_LENGTH_SIZE + FAMILY_LENGTH_SIZE + rightRowLength;
      int leftCommonLengthWithTSAndType = TIMESTAMP_TYPE_SIZE + leftCommonLength;
      int rightCommonLengthWithTSAndType = TIMESTAMP_TYPE_SIZE + rightCommonLength;
      int leftColumnLength = leftKey.length - leftCommonLengthWithTSAndType;
      int rightColumnLength = rightKey.length - rightCommonLengthWithTSAndType;
      // rows are equal
      if (leftRowLength == rightRowLength && compareRows(leftKey, ROW_LENGTH_SIZE, leftRowLength,
        rightKey, ROW_LENGTH_SIZE, rightRowLength) == 0) {
        // Compare family & qualifier together.
        int comparison = Bytes.compareTo(leftKey, leftCommonLength, leftColumnLength, rightKey,
          rightCommonLength, rightColumnLength);
        // same with "row + family + qualifier", return rightKey directly
        if (comparison == 0) {
          return Arrays.copyOf(rightKey, rightKey.length);
        }
        // "family + qualifier" are different, generate a faked key per rightKey
        byte[] newKey = Arrays.copyOf(rightKey, rightKey.length);
        Bytes.putLong(newKey, rightKey.length - TIMESTAMP_TYPE_SIZE, HConstants.LATEST_TIMESTAMP);
        Bytes.putByte(newKey, rightKey.length - TYPE_SIZE, Type.Maximum.getCode());
        return newKey;
      }
      // rows are different
      short minLength = leftRowLength < rightRowLength ? leftRowLength : rightRowLength;
      short diffIdx = 0;
      while (diffIdx < minLength
          && leftKey[ROW_LENGTH_SIZE + diffIdx] == rightKey[ROW_LENGTH_SIZE + diffIdx]) {
        diffIdx++;
      }
      byte[] newRowKey = null;
      if (diffIdx >= minLength) {
        // leftKey's row is prefix of rightKey's.
        newRowKey = new byte[diffIdx + 1];
        System.arraycopy(rightKey, ROW_LENGTH_SIZE, newRowKey, 0, diffIdx + 1);
      } else {
        int diffByte = leftKey[ROW_LENGTH_SIZE + diffIdx];
        if ((0xff & diffByte) < 0xff && (diffByte + 1) <
            (rightKey[ROW_LENGTH_SIZE + diffIdx] & 0xff)) {
          newRowKey = new byte[diffIdx + 1];
          System.arraycopy(leftKey, ROW_LENGTH_SIZE, newRowKey, 0, diffIdx);
          newRowKey[diffIdx] = (byte) (diffByte + 1);
        } else {
          newRowKey = new byte[diffIdx + 1];
          System.arraycopy(rightKey, ROW_LENGTH_SIZE, newRowKey, 0, diffIdx + 1);
        }
      }
      return new KeyValue(newRowKey, null, null, HConstants.LATEST_TIMESTAMP,
        Type.Maximum).getKey();
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
      super.clone();
      return new KVComparator();
    }

  }

  /**
   * @param b
   * @return A KeyValue made of a byte array that holds the key-only part.
   * Needed to convert hfile index members to KeyValues.
   */
  public static KeyValue createKeyValueFromKey(final byte [] b) {
    return createKeyValueFromKey(b, 0, b.length);
  }

  /**
   * @param bb
   * @return A KeyValue made of a byte buffer that holds the key-only part.
   * Needed to convert hfile index members to KeyValues.
   */
  public static KeyValue createKeyValueFromKey(final ByteBuffer bb) {
    return createKeyValueFromKey(bb.array(), bb.arrayOffset(), bb.limit());
  }

  /**
   * @param b
   * @param o
   * @param l
   * @return A KeyValue made of a byte array that holds the key-only part.
   * Needed to convert hfile index members to KeyValues.
   */
  public static KeyValue createKeyValueFromKey(final byte [] b, final int o,
      final int l) {
    byte [] newb = new byte[l + ROW_OFFSET];
    System.arraycopy(b, o, newb, ROW_OFFSET, l);
    Bytes.putInt(newb, 0, l);
    Bytes.putInt(newb, Bytes.SIZEOF_INT, 0);
    return new KeyValue(newb);
  }

  /**
   * @param in Where to read bytes from.  Creates a byte array to hold the KeyValue
   * backing bytes copied from the steam.
   * @return KeyValue created by deserializing from <code>in</code> OR if we find a length
   * of zero, we will return null which can be useful marking a stream as done.
   * @throws IOException
   */
  public static KeyValue create(final DataInput in) throws IOException {
    return create(in.readInt(), in);
  }

  /**
   * Create a KeyValue reading <code>length</code> from <code>in</code>
   * @param length
   * @param in
   * @return Created KeyValue OR if we find a length of zero, we will return null which
   * can be useful marking a stream as done.
   * @throws IOException
   */
  public static KeyValue create(int length, final DataInput in) throws IOException {

    if (length <= 0) {
      if (length == 0) return null;
      throw new IOException("Failed read " + length + " bytes, stream corrupt?");
    }

    // This is how the old Writables.readFrom used to deserialize.  Didn't even vint.
    byte [] bytes = new byte[length];
    in.readFully(bytes);
    return new KeyValue(bytes, 0, length);
  }
  
  /**
   * Create a new KeyValue by copying existing cell and adding new tags
   * @param c
   * @param newTags
   * @return a new KeyValue instance with new tags
   */
  public static KeyValue cloneAndAddTags(Cell c, List<Tag> newTags) {
    List<Tag> existingTags = null;
    if(c.getTagsLength() > 0) {
      existingTags = Tag.asList(c.getTagsArray(), c.getTagsOffset(), c.getTagsLength());
      existingTags.addAll(newTags);
    } else {
      existingTags = newTags;
    }
    return new KeyValue(c.getRowArray(), c.getRowOffset(), (int)c.getRowLength(),
      c.getFamilyArray(), c.getFamilyOffset(), (int)c.getFamilyLength(), 
      c.getQualifierArray(), c.getQualifierOffset(), (int) c.getQualifierLength(), 
      c.getTimestamp(), Type.codeToType(c.getTypeByte()), c.getValueArray(), c.getValueOffset(), 
      c.getValueLength(), existingTags);
  }

  /**
   * Create a KeyValue reading from the raw InputStream.
   * Named <code>iscreate</code> so doesn't clash with {@link #create(DataInput)}
   * @param in
   * @return Created KeyValue OR if we find a length of zero, we will return null which
   * can be useful marking a stream as done.
   * @throws IOException
   */
  public static KeyValue iscreate(final InputStream in) throws IOException {
    byte [] intBytes = new byte[Bytes.SIZEOF_INT];
    int bytesRead = 0;
    while (bytesRead < intBytes.length) {
      int n = in.read(intBytes, bytesRead, intBytes.length - bytesRead);
      if (n < 0) {
        if (bytesRead == 0) return null; // EOF at start is ok
        throw new IOException("Failed read of int, read " + bytesRead + " bytes");
      }
      bytesRead += n;
    }
    // TODO: perhaps some sanity check is needed here.
    byte [] bytes = new byte[Bytes.toInt(intBytes)];
    IOUtils.readFully(in, bytes, 0, bytes.length);
    return new KeyValue(bytes, 0, bytes.length);
  }

  /**
   * Write out a KeyValue in the manner in which we used to when KeyValue was a Writable.
   * @param kv
   * @param out
   * @return Length written on stream
   * @throws IOException
   * @see #create(DataInput) for the inverse function
   */
  public static long write(final KeyValue kv, final DataOutput out) throws IOException {
    // This is how the old Writables write used to serialize KVs.  Need to figure way to make it
    // work for all implementations.
    int length = kv.getLength();
    out.writeInt(length);
    out.write(kv.getBuffer(), kv.getOffset(), length);
    return length + Bytes.SIZEOF_INT;
  }

  /**
   * Write out a KeyValue in the manner in which we used to when KeyValue was a Writable but do
   * not require a {@link DataOutput}, just take plain {@link OutputStream}
   * Named <code>oswrite</code> so does not clash with {@link #write(KeyValue, DataOutput)}
   * @param kv
   * @param out
   * @return Length written on stream
   * @throws IOException
   * @see #create(DataInput) for the inverse function
   * @see #write(KeyValue, DataOutput)
   * @deprecated use {@link #oswrite(KeyValue, OutputStream, boolean)} instead
   */
  @Deprecated
  public static long oswrite(final KeyValue kv, final OutputStream out)
      throws IOException {
    int length = kv.getLength();
    // This does same as DataOuput#writeInt (big-endian, etc.)
    out.write(Bytes.toBytes(length));
    out.write(kv.getBuffer(), kv.getOffset(), length);
    return length + Bytes.SIZEOF_INT;
  }

  /**
   * Write out a KeyValue in the manner in which we used to when KeyValue was a Writable but do
   * not require a {@link DataOutput}, just take plain {@link OutputStream}
   * Named <code>oswrite</code> so does not clash with {@link #write(KeyValue, DataOutput)}
   * @param kv
   * @param out
   * @param withTags
   * @return Length written on stream
   * @throws IOException
   * @see #create(DataInput) for the inverse function
   * @see #write(KeyValue, DataOutput)
   * @see KeyValueUtil#oswrite(Cell, OutputStream, boolean)
   */
  public static long oswrite(final KeyValue kv, final OutputStream out, final boolean withTags)
      throws IOException {
    // In KeyValueUtil#oswrite we do a Cell serialization as KeyValue. Any changes doing here, pls
    // check KeyValueUtil#oswrite also and do necessary changes.
    int length = kv.getLength();
    if (!withTags) {
      length = kv.getKeyLength() + kv.getValueLength() + KEYVALUE_INFRASTRUCTURE_SIZE;
    }
    // This does same as DataOuput#writeInt (big-endian, etc.)
    StreamUtils.writeInt(out, length);
    out.write(kv.getBuffer(), kv.getOffset(), length);
    return length + Bytes.SIZEOF_INT;
  }

  /**
   * Comparator that compares row component only of a KeyValue.
   */
  public static class RowOnlyComparator implements Comparator<KeyValue> {
    final KVComparator comparator;

    public RowOnlyComparator(final KVComparator c) {
      this.comparator = c;
    }

    public int compare(KeyValue left, KeyValue right) {
      return comparator.compareRows(left, right);
    }
  }


  /**
   * Avoids redundant comparisons for better performance.
   * 
   * TODO get rid of this wart
   */
  public interface SamePrefixComparator<T> {
    /**
     * Compare two keys assuming that the first n bytes are the same.
     * @param commonPrefix How many bytes are the same.
     */
    int compareIgnoringPrefix(
      int commonPrefix, byte[] left, int loffset, int llength, byte[] right, int roffset, int rlength
    );
  }

  /**
   * This is a TEST only Comparator used in TestSeekTo and TestReseekTo.
   */
  public static class RawBytesComparator extends KVComparator {
    /**
     * The HFileV2 file format's trailer contains this class name.  We reinterpret this and
     * instantiate the appropriate comparator.
     * TODO: With V3 consider removing this.
     * @return legacy class name for FileFileTrailer#comparatorClassName
     */
    public String getLegacyKeyComparatorName() {
      return "org.apache.hadoop.hbase.util.Bytes$ByteArrayComparator";
    }

    /**
     * @deprecated Since 0.99.2.
     */
    @Deprecated
    public int compareFlatKey(byte[] left, int loffset, int llength, byte[] right,
        int roffset, int rlength) {
      return Bytes.BYTES_RAWCOMPARATOR.compare(left,  loffset, llength, right, roffset, rlength);
    }

    @Override
    public int compare(Cell left, Cell right) {
      return compareOnlyKeyPortion(left, right);
    }

    @VisibleForTesting
    public int compareOnlyKeyPortion(Cell left, Cell right) {
      int c = Bytes.BYTES_RAWCOMPARATOR.compare(left.getRowArray(), left.getRowOffset(),
          left.getRowLength(), right.getRowArray(), right.getRowOffset(), right.getRowLength());
      if (c != 0) {
        return c;
      }
      c = Bytes.BYTES_RAWCOMPARATOR.compare(left.getFamilyArray(), left.getFamilyOffset(),
          left.getFamilyLength(), right.getFamilyArray(), right.getFamilyOffset(),
          right.getFamilyLength());
      if (c != 0) {
        return c;
      }
      c = Bytes.BYTES_RAWCOMPARATOR.compare(left.getQualifierArray(), left.getQualifierOffset(),
          left.getQualifierLength(), right.getQualifierArray(), right.getQualifierOffset(),
          right.getQualifierLength());
      if (c != 0) {
        return c;
      }
      c = compareTimestamps(left.getTimestamp(), right.getTimestamp());
      if (c != 0) {
        return c;
      }
      return (0xff & left.getTypeByte()) - (0xff & right.getTypeByte());
    }

    public byte[] calcIndexKey(byte[] lastKeyOfPreviousBlock, byte[] firstKeyInBlock) {
      return firstKeyInBlock;
    }

  }

  /**
   * HeapSize implementation
   *
   * We do not count the bytes in the rowCache because it should be empty for a KeyValue in the
   * MemStore.
   */
  @Override
  public long heapSize() {
    int sum = 0;
    sum += ClassSize.OBJECT;// the KeyValue object itself
    sum += ClassSize.REFERENCE;// pointer to "bytes"
    sum += ClassSize.align(ClassSize.ARRAY);// "bytes"
    sum += ClassSize.align(length);// number of bytes of data in the "bytes" array
    sum += 2 * Bytes.SIZEOF_INT;// offset, length
    sum += Bytes.SIZEOF_LONG;// memstoreTS
    return ClassSize.align(sum);
  }

  /**
   * A simple form of KeyValue that creates a keyvalue with only the key part of the byte[]
   * Mainly used in places where we need to compare two cells.  Avoids copying of bytes
   * In places like block index keys, we need to compare the key byte[] with a cell.
   * Hence create a Keyvalue(aka Cell) that would help in comparing as two cells
   */
  public static class KeyOnlyKeyValue extends KeyValue {
    private int length = 0;
    private int offset = 0;
    private byte[] b;

    public KeyOnlyKeyValue() {

    }

    public KeyOnlyKeyValue(byte[] b, int offset, int length) {
      this.b = b;
      this.length = length;
      this.offset = offset;
    }

    @Override
    public int getKeyOffset() {
      return this.offset;
    }

    /**
     * A setter that helps to avoid object creation every time and whenever
     * there is a need to create new KeyOnlyKeyValue.
     * @param key
     * @param offset
     * @param length
     */
    public void setKey(byte[] key, int offset, int length) {
      this.b = key;
      this.offset = offset;
      this.length = length;
    }

    @Override
    public byte[] getKey() {
      int keylength = getKeyLength();
      byte[] key = new byte[keylength];
      System.arraycopy(this.b, getKeyOffset(), key, 0, keylength);
      return key;
    }

    @Override
    public byte[] getRowArray() {
      return b;
    }

    @Override
    public int getRowOffset() {
      return getKeyOffset() + Bytes.SIZEOF_SHORT;
    }

    @Override
    public byte[] getFamilyArray() {
      return b;
    }

    @Override
    public byte getFamilyLength() {
      return this.b[getFamilyOffset() - 1];
    }

    @Override
    public int getFamilyOffset() {
      return this.offset + Bytes.SIZEOF_SHORT + getRowLength() + Bytes.SIZEOF_BYTE;
    }

    @Override
    public byte[] getQualifierArray() {
      return b;
    }

    @Override
    public int getQualifierLength() {
      return getQualifierLength(getRowLength(), getFamilyLength());
    }

    @Override
    public int getQualifierOffset() {
      return getFamilyOffset() + getFamilyLength();
    }

    @Override
    public int getKeyLength() {
      return length;
    }

    @Override
    public short getRowLength() {
      return Bytes.toShort(this.b, getKeyOffset());
    }

    @Override
    public byte getTypeByte() {
      return this.b[this.offset + getKeyLength() - 1];
    }

    private int getQualifierLength(int rlength, int flength) {
      return getKeyLength() - (int) getKeyDataStructureSize(rlength, flength, 0);
    }

    @Override
    public long getTimestamp() {
      int tsOffset = getTimestampOffset();
      return Bytes.toLong(this.b, tsOffset);
    }

    @Override
    public int getTimestampOffset() {
      return getKeyOffset() + getKeyLength() - TIMESTAMP_TYPE_SIZE;
    }

    @Override
    public byte[] getTagsArray() {
      return HConstants.EMPTY_BYTE_ARRAY;
    }

    @Override
    public int getTagsOffset() {
      return 0;
    }

    @Override
    public byte[] getValueArray() {
      throw new IllegalArgumentException("KeyOnlyKeyValue does not work with values.");
    }

    @Override
    public int getValueOffset() {
      throw new IllegalArgumentException("KeyOnlyKeyValue does not work with values.");
    }

    @Override
    public int getValueLength() {
      throw new IllegalArgumentException("KeyOnlyKeyValue does not work with values.");
    }

    @Override
    public int getTagsLength() {
      return 0;
    }

    @Override
    public String toString() {
      if (this.b == null || this.b.length == 0) {
        return "empty";
      }
      return keyToString(this.b, this.offset, getKeyLength()) + "/vlen=0/mvcc=0";
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return super.equals(other);
    }
  }
}
