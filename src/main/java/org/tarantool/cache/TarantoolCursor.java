/**
 *  Copyright 2011-2013 Terracotta, Inc.
 *  Copyright 2011-2013 Oracle America Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.tarantool.cache;

import static java.util.Collections.singletonList;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.cache.Cache.Entry;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Evgeniy Zaikin
 */
public class TarantoolCursor<K, V> implements Entry<K, V> {
  private static final Logger log = LoggerFactory.getLogger(TarantoolCursor.class);

  private static enum CursorType {
      UNDEFINED,
      SERVER,
      CLIENT
  };

  /**
   * The {@link CursorType} represents current state of {@link TarantoolCursor}
   */
  private CursorType cursorType = CursorType.UNDEFINED;

  /**
   * The {@link TarantoolSpace} is Space representation of this cache
   */
  private final TarantoolSpace space;

  /**
   * The {@link ExpiryPolicy} for the {@link Cache}.
   */
  private final ExpiryPolicy expiryPolicy;

  /**
   * Current tuple container, consist of key, value, expiryTime
   */
  private final List<Object> tuple;

  /**
   * This list holds an Update operation for value field, looks like ("=", 1, value)
   */
  private final List<Object> updateValueOperation;

  /**
   * This list holds an Update operation for expiryTime field, looks like ("=", 2, expiryTime)
   */
  private final List<Object> updateExpiryOperation;

  /**
   * The default Duration to use when a Duration can't be determined.
   *
   * @return the default Duration
   */
  private Duration getDefaultDuration() {
      return Duration.ETERNAL;
  }

  /**
   * Invalidate all the fields.
   */
  private void invalidate() {
      tuple.set(0, null);
      tuple.set(1, null);
      tuple.set(2, null);
  }

  /**
   * Sets the time (since the Epoch) in milliseconds when the tuple
   * associated with this value should be considered expired.
   *
   * @param expiryTime time in milliseconds (since the Epoch)
   */
  private void setExpiryTime(long expiryTime) {
      tuple.set(2, expiryTime);
      updateExpiryOperation.set(2, expiryTime);
  }

  /**
   * Parses given entry (tuple) and puts fields value to
   * the internal container.
   *
   * @param entry to be parsed, must be List of fields
   * @throws TarantoolCacheException if incorrect tuple was selected from space
   */
  private void parse(Object entry) {
      if (entry instanceof List) {
          List<?> fields = (List<?>) entry;
          /* Check here field count, it must be 3 (key, value, expiryTime) */
          if (fields.size() == 3) {
              tuple.set(0, fields.get(0));
              tuple.set(1, fields.get(1));
              tuple.set(2, fields.get(2));
              return;
          } else if (fields.size() > 0) {
              /* Delete tuple from Tarantool in case tuple is corrupted */
              //space.delete(singletonList(fields.get(0)));
          }
      }

      throw new TarantoolCacheException("Tuple with incorrect fields was selected from " + space.toString());
  }

  /**
   * Constructs an {@link TarantoolCursor} and bind it with an existing {@link TarantoolSpace},
   * and with {@link ExpiryPolicy}
   * @param space {@link TarantoolSpace} to be used with {@link TarantoolCursor}
   * @param expiryPolicy used for obtaining Expiration Duration for Create, Update, Access
   */
  public TarantoolCursor(TarantoolSpace space, ExpiryPolicy expiryPolicy) {
      this.space = space;
      this.expiryPolicy = expiryPolicy;
      tuple = Arrays.asList(null, null, null);
      updateValueOperation = Arrays.asList("=", 1, null);
      updateExpiryOperation = Arrays.asList("=", 2, null);
  }

  /**
   * Select Tarantool's tuple and set {@link TarantoolCursor} to it
   *
   * @param K key which is used for select tuple
   * @throws NullPointerException if a given key is null
   */
  public boolean locate(K key) {
      if (key == null) {
          throw new NullPointerException();
      }
      final Iterator<?> iterator = space.select(singletonList(key)).iterator();
      if (iterator.hasNext()) {
          cursorType = CursorType.SERVER;
          parse(iterator.next());
          return true;
      }
      cursorType = CursorType.UNDEFINED;
      return false;
  }

  /**
   * Select first (top) Tarantool's tuple from Space, build iterator,
   * which can be used to iterate next Tuple.
   *
   * @param now      the time the iterator will use to test for expiry
   */
  public Iterator<TarantoolCursor<K, V>> iterator(long now) {
      Iterator<TarantoolCursor<K, V>> iterator = new Iterator<TarantoolCursor<K, V>>() {
          private Iterator<?> iterator = space.first().iterator();

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public TarantoolCursor<K, V> next() {
            if (iterator.hasNext()) {
              // It is still current tuple, parse it
              parse(iterator.next());
              // Set cursor type to CursorType.SERVER
              cursorType = CursorType.SERVER;
              // Update tuple access time
              access(now);
              // Fetch next tuple for the future, do not parse it now
              iterator = space.next(singletonList(getKey())).iterator();
              // Return current parsed cursor
              return TarantoolCursor.this;
            } else {
              throw new NoSuchElementException();
            }
          }

          @Override
          public void remove() {
            if (cursorType != CursorType.SERVER) {
                throw new IllegalStateException("Must progress to the next entry to remove");
              } else {
                delete();
              }
          }
      };

      // Set cursor type to CursorType.UNDEFINED, until progress to the first entry
      cursorType = CursorType.UNDEFINED;
      invalidate();
      return iterator;
  }

  /**
   * Select all Tarantool's tuple from Space, build iterator wrapper
   * for iterating over selected tuples.
   * Cursor is opening in client mode ({@code CursorType.CLIENT}).
   * Note: only ReadOnly mode is available, remove() is not supported.
   */
  public Iterator<TarantoolCursor<K, V>> open() {
      final Iterator<?> iterator = space.select().iterator();
      cursorType = CursorType.CLIENT;
      return new Iterator<TarantoolCursor<K, V>>() {

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public TarantoolCursor<K, V> next() {
            parse(iterator.next());
            return TarantoolCursor.this;
          }
      };
  }

  /**
   * Delete Tarantool's tuple by given key, and if succeeded set {@link TarantoolCursor} to
   * deleted tuple. Cursor's state is changed to CursorType.CLIENT,
   * it means that cursor is detached from the Space.
   *
   * @param K key which is used for select tuple
   * @throws NullPointerException if a given key is null
   */
  public boolean delete(K key) {
      if (key == null) {
          throw new NullPointerException();
      }
      final Iterator<?> iterator = space.delete(singletonList(key)).iterator();
      if (iterator.hasNext()) {
          // Change cursor type to forbid attempt to remove tuple again
          cursorType = CursorType.CLIENT;
          parse(iterator.next());
          return true;
      }
      cursorType = CursorType.UNDEFINED;
      return false;
  }

  /**
   * Delete Tarantool's tuple, and if succeeded set {@link TarantoolCursor} to
   * deleted tuple. Cursor's state is changed to CursorType.CLIENT,
   * it means that cursor is detached from the Space.
   *
   * @throws IllegalStateException if cursor is not opened
   */
  public boolean delete() {
      // Check state of cursor here,
      // Also forbid attempt to remove tuple again if it already removed
      if (cursorType != CursorType.SERVER) {
          throw new IllegalStateException("Cursor is not opened in Server Mode");
      }
      return delete(getKey());
  }

  /**
   * Perform Insert operation, and if succeeded set {@link TarantoolCursor} to
   * inserted tuple. Cursor's state is changed to CursorType.SERVER,
   * it means that cursor is active and attached to the Space.
   * @param key      the internal representation of the key
   * @param value    the internal representation of the value
   * @param now      the time when the cache entry was created
   * @return true    if newly created value hasn't already expired, false otherwise
   */
  public boolean insert(K key, V value, long now) {
    if (key == null) {
      throw new NullPointerException();
    }
    Duration duration;
    try {
        duration = expiryPolicy.getExpiryForCreation();
    } catch (Throwable t) {
        duration = getDefaultDuration();
    }
    long expiryTime = duration.getAdjustedTime(now);
    // check that new entry is not already expired, in which case it should
    // not be added to the cache or listeners called or writers called.
    if (expiryTime > -1 && expiryTime <= now) {
        return false;
    }

    tuple.set(0, key);
    tuple.set(1, value);
    tuple.set(2, expiryTime);

    space.insert(tuple);
    cursorType = CursorType.SERVER;
    return true;
  }

  /**
   * Returns true if {@link TarantoolCursor} is attached to space
   *
   * @return boolean
   */
  public boolean isLocated() {
    return cursorType == CursorType.SERVER;
  }

  /**
   * Determines if the Cache Entry associated with this value would be expired
   * at the specified time
   *
   * @param now time in milliseconds (since the Epoch)
   * @return true if the value would be expired at the specified time
   * @throws IllegalStateException if cursor is not opened
   */
  public boolean isExpiredAt(long now) {
    if (cursorType == CursorType.UNDEFINED) {
      throw new IllegalStateException("Cursor is not opened");
    }
    long expiryTime = Long.class.cast(tuple.get(2));
    return expiryTime > -1 && expiryTime <= now;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public K getKey() {
    return (K)tuple.get(0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public V getValue() {
    //Gets the value (without updating the access time).
    return (V)tuple.get(1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T> T unwrap(Class<T> clazz) {
    if (clazz != null && clazz.isInstance(this)) {
      return (T) this;
    } else if (clazz == org.tarantool.jsr107.CacheEntry.class) {
      org.tarantool.jsr107.CacheEntry<?,?> e = new org.tarantool.jsr107.CacheEntry<K,V>(getKey(), getValue());
      return (T) e;
    } else {
      throw new IllegalArgumentException("Class " + clazz + " is unknown to this implementation");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    return getKey().hashCode();
  }

  /**
   * Gets the value with the side-effect of updating the access time
   * to that which is specified.
   *
   * @param accessTime the time when the value was accessed
   * @return the value
   */
  public V fetch(long accessTime) {
    access(accessTime);
    return getValue();
  }

  /**
   * Sets the internal value with the additional side-effect of updating the
   * modification time to that which is specified.
   *
   * @param newValue         the new internal value
   * @param modificationTime the time when the value was modified
   * @throws IllegalStateException if cursor is not opened
   */
  public void update(V newValue, long modificationTime) {
    if (cursorType != CursorType.SERVER) {
        throw new IllegalStateException("Cursor is not opened");
    }

    Duration duration = null;
    // even if the tuple exists we should check whether it is not expired,
    // and if it is, we don't delete expired tuple here,
    // performing forced update with creation time instead
    if (isExpiredAt(modificationTime)) {
        try {
            duration = expiryPolicy.getExpiryForCreation();
        } catch (Throwable t) {
            duration = getDefaultDuration();
        }
    } else {
        try {
            duration = expiryPolicy.getExpiryForUpdate();
        } catch (Throwable t) {
            //leave the expiry time untouched when we can't determine a duration
            log.error("Exception occurred during determination expire policy for Update", t);
        }
    }

    updateValueOperation.set(2, newValue);

    if (duration != null) {
        long expiryTime = duration.getAdjustedTime(modificationTime);
        // set new calculated expiryTime
        setExpiryTime(expiryTime);
        // And check whether Tuple with new expiryTime becomes expired
        // and if it is, we must delete expired tuple right here
        if (!isExpiredAt(modificationTime)) {
            space.update(singletonList(getKey()), updateValueOperation, updateExpiryOperation);
        } else {
            delete();
        }
    } else {
        //leave the expiry time untouched when duration is undefined
        space.update(singletonList(getKey()), updateValueOperation);
    }
  }

  /**
   * Updates the access time to that which is specified.
   *
   * @param accessTime the time when the value was accessed
   */
  public void access(long accessTime) {
    if (cursorType != CursorType.SERVER) {
        throw new IllegalStateException("Cursor is not opened");
    }
    try {
        Duration duration = expiryPolicy.getExpiryForAccess();
        if (duration != null) {
            long expiryTime = duration.getAdjustedTime(accessTime);
            // set new calculated expiryTime for access
            setExpiryTime(expiryTime);
            // And check whether Tuple with new expiryTime becomes expired
            // and if it is, we must delete expired tuple right here
            if (!isExpiredAt(accessTime)) {
                space.update(singletonList(getKey()), updateExpiryOperation);
            } else {
                delete();
            }
        }
    } catch (Throwable t) {
        //leave the expiry time untouched when we can't determine a duration
        log.error("Exception occurred during determination expire policy for Access", t);
    }
  }

  /**
   * Closes cursor, detaching from Tarantool's space
   */
  public void close() {
    cursorType = CursorType.UNDEFINED;
  }

}
