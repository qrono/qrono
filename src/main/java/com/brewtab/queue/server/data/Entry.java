package com.brewtab.queue.server.data;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@Value.Enclosing
public interface Entry extends Comparable<Entry> {
  Key key();

  @Nullable
  Item item();

  default boolean isPending() {
    return key().entryType() == Type.PENDING;
  }

  default boolean isTombstone() {
    return key().entryType() == Type.TOMBSTONE;
  }

  @Value.Check
  default void check() {
    if (isTombstone()) {
      Preconditions.checkArgument(item() == null, "tombstone entry must have null item");
    } else {
      Preconditions.checkArgument(isPending(), "entry must be pending or tombstone");
      Preconditions.checkArgument(item() != null, "pending entry must have non-null item");
    }
  }

  @Override
  default int compareTo(Entry o) {
    return key().compareTo(o.key());
  }

  static Entry tombstoneFrom(Key key) {
    return ImmutableEntry.builder()
        .key(ImmutableEntry.Key.builder()
            .from(key)
            .entryType(Type.TOMBSTONE)
            .build())
        .build();
  }

  static Entry tombstoneFrom(Item item) {
    return ImmutableEntry.builder()
        .key(ImmutableEntry.Key.builder()
            .deadline(item.deadline())
            .id(item.id())
            .entryType(Type.TOMBSTONE)
            .build())
        .build();
  }

  static Entry pendingFrom(Item item) {
    return ImmutableEntry.builder()
        .key(ImmutableEntry.Key.builder()
            .deadline(item.deadline())
            .id(item.id())
            .entryType(Type.PENDING)
            .build())
        .item(item)
        .build();
  }

  @Value.Immutable
  interface Key extends Comparable<Key> {
    Timestamp deadline();

    long id();

    // TODO: Should this be excluded from equals/hashCode/toString?
    //  Currently its difficult to compare corresponding PENDING/TOMBSTONE keys for equality.
    // @Value.Auxiliary
    Type entryType();

    @Override
    default int compareTo(Key o) {
      int cmp = deadline().compareTo(o.deadline());
      if (cmp != 0) {
        return cmp;
      }

      cmp = Long.compare(id(), o.id());
      if (cmp != 0) {
        return cmp;
      }

      return entryType().compareTo(o.entryType());
    }
  }

  enum Type {
    // nb. TOMBSTONE must be declared before PENDING for key ordering to be correct.
    TOMBSTONE,
    PENDING
  }
}