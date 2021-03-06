/*
 * Copyright (c) 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.truth.extensions.proto;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.truth.extensions.proto.MessageDifferencer.IgnoreCriteria;
import com.google.common.truth.extensions.proto.MessageDifferencer.SpecificField;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Implementations of {@link FieldScope} and {@link FieldScopes} routines.
 *
 * <p>{@code FieldScopeImpl} is the abstract base class which provides common functionality to all
 * sub-types. There are two classes of sub-types: Concrete subtypes, which implements specific rules
 * and perform no delegation, and Compound subtypes, which combine one or more {@code
 * FieldScopeImpls} with specific operations.
 */
abstract class FieldScopeImpl extends FieldScope {

  private final Set<Descriptor> validatedDescriptors = Sets.newConcurrentHashSet();

  @Override
  final IgnoreCriteria toIgnoreCriteria(final Descriptor descriptor) {
    if (!validatedDescriptors.contains(descriptor)) {
      validate(descriptor);
      validatedDescriptors.add(descriptor);
    }

    final Cache cache = new Cache();
    return new IgnoreCriteria() {
      @Override
      public boolean isIgnored(
          Message message1,
          Message message2,
          @Nullable FieldDescriptor fieldDescriptor,
          List<SpecificField> fieldPath) {
        ImmutableList.Builder<Message> subMessages = ImmutableList.builder();
        if (fieldDescriptor != null) {
          addSubMessages(fieldDescriptor, message1, subMessages);
          addSubMessages(fieldDescriptor, message2, subMessages);
        }

        Context context = Context.create(descriptor, fieldPath, fieldDescriptor, subMessages);
        return !matchesContentOfSubMessages(context, cache) && !matchesFieldPath(context, cache);
      }
    };
  }

  private static void addSubMessages(
      FieldDescriptor fieldDescriptor, Message message, ImmutableList.Builder<Message> builder) {
    if (fieldDescriptor.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
      return;
    }

    if (fieldDescriptor.isRepeated()) {
      for (int i = 0; i < message.getRepeatedFieldCount(fieldDescriptor); i++) {
        builder.add((Message) message.getRepeatedField(fieldDescriptor, i));
      }
    } else {
      builder.add((Message) message.getField(fieldDescriptor));
    }
  }

  // A temporary cache for repeat processing of messages in a single MessageDifferencer run.
  // Cache data must be temporary because messages may be mutable, and change between
  // MessageDifferencer runs, which invalidates them as keys and invalidates the results.
  private static final class Cache {
    private final Map<FieldMatcherBaseScopeImpl, Map<Message, Boolean>> messagesWithMatchingField =
        Maps.newHashMap();

    public Map<Message, Boolean> getMessagesWithMatchingField(FieldMatcherBaseScopeImpl key) {
      Map<Message, Boolean> map = messagesWithMatchingField.get(key);
      if (map == null) {
        map = Maps.newHashMap();
        messagesWithMatchingField.put(key, map);
      }
      return map;
    }
  }

  @AutoValue
  abstract static class Context {

    /** The Message Descriptor for the message being tested. */
    abstract Descriptor descriptor();

    /**
     * The specific field path leading up to the message containing {@code field()}. If {@code
     * field()} is absent, the last element of fieldPath() will describe an unknown field instead.
     */
    abstract List<SpecificField> fieldPath();

    /**
     * The field on the message being inspected, for which we must now determine if it should be
     * inspected deeply or not.
     */
    abstract Optional<FieldDescriptor> field();

    /**
     * The message objects at the end of the field path and field descriptor, or empty if there are
     * none. MessageDifferencer will omit entire sections of the proto tree if told to ignore root
     * messages, so we may need to inspect the contents of the message first to decide if it should
     * be ignored.
     */
    abstract ImmutableList<Message> messageFields();

    static Context create(
        Descriptor descriptor,
        List<SpecificField> fieldPath,
        @Nullable FieldDescriptor field,
        ImmutableList.Builder<Message> messageFields) {
      return new AutoValue_FieldScopeImpl_Context(
          descriptor, fieldPath, Optional.fromNullable(field), messageFields.build());
    }
  }

  /** Whether or not this implementation includes the specified specific field path. */
  abstract boolean matchesFieldPath(Context context, Cache cache);

  /**
   * Whether or not this implementation matches some sub-field of one of the messages at the end of
   * the field path. Necessary for negation to work with arbitrary FieldDescriptors.
   *
   * <p>Returns false by default, since most FieldScopeImpls don't inspect sub-message contents.
   */
  boolean matchesContentOfSubMessages(Context context, Cache cache) {
    return false;
  }

  /**
   * Performs any validation that requires a Descriptor to validate against.
   *
   * @throws IllegalArgumentException if invalid input was provided
   */
  void validate(Descriptor descriptor) {}

  private static boolean isEmpty(int... ints) {
    return ints.length == 0;
  }

  private static boolean isEmpty(FieldDescriptor... fieldDescriptors) {
    for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
      checkNotNull(fieldDescriptor);
    }

    return fieldDescriptors.length == 0;
  }

  @Override
  public FieldScope ignoringFields(int... fieldNumbers) {
    if (isEmpty(fieldNumbers)) {
      return this;
    }
    return and(this, new NegationScopeImpl(new FieldNumbersScopeImpl(fieldNumbers)));
  }

  @Override
  public FieldScope ignoringFieldDescriptors(FieldDescriptor... fieldDescriptors) {
    if (isEmpty(fieldDescriptors)) {
      return this;
    }
    return and(this, new NegationScopeImpl(new FieldDescriptorsScopeImpl(fieldDescriptors)));
  }

  @Override
  public FieldScope allowingFields(int... fieldNumbers) {
    if (isEmpty(fieldNumbers)) {
      return this;
    }
    return or(this, new FieldNumbersScopeImpl(fieldNumbers));
  }

  @Override
  public FieldScope allowingFieldDescriptors(FieldDescriptor... fieldDescriptors) {
    if (isEmpty(fieldDescriptors)) {
      return this;
    }
    return or(this, new FieldDescriptorsScopeImpl(fieldDescriptors));
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // CONCRETE SUBTYPES
  //////////////////////////////////////////////////////////////////////////////////////////////////

  private static final FieldScope ALL =
      new FieldScopeImpl() {
        @Override
        boolean matchesFieldPath(Context context, Cache cache) {
          return true;
        }
      };
  private static final FieldScope NONE =
      new FieldScopeImpl() {
        @Override
        boolean matchesFieldPath(Context context, Cache cache) {
          return false;
        }
      };

  static FieldScope all() {
    return ALL;
  }

  static FieldScope none() {
    return NONE;
  }

  private static final class PartialScopeImpl extends FieldScopeImpl {
    private final FieldNumberTree fieldNumberTree;
    private final Descriptor expectedDescriptor;

    PartialScopeImpl(Message message) {
      this.fieldNumberTree = FieldNumberTree.fromMessage(message);
      this.expectedDescriptor = message.getDescriptorForType();
    }

    @Override
    void validate(Descriptor descriptor) {
      Preconditions.checkArgument(
          expectedDescriptor.equals(descriptor),
          "Message given to FieldScopes.fromSetFields() does not have the same descriptor as the "
              + "message being tested. Expected %s, got %s.",
          expectedDescriptor,
          descriptor);
    }

    @Override
    boolean matchesFieldPath(Context context, Cache cache) {
      return fieldNumberTree.matches(context.fieldPath(), context.field());
    }
  }

  static FieldScope partialScope(Message message) {
    return new PartialScopeImpl(message);
  }

  // TODO(user): Performance: Optimize FieldNumbersScopeImpl and FieldDescriptorsScopeImpl for
  // adding / ignoring field numbers and descriptors, respectively, to eliminate high recursion
  // costs for long chains of allows/ignores.

  // Common functionality for FieldNumbersScopeImpl and FieldDescriptorsScopeImpl.
  private abstract static class FieldMatcherBaseScopeImpl extends FieldScopeImpl {

    /**
     * Determines whether the FieldDescriptor is equal to one of the explicitly defined components
     * of this FieldScopeImpl.
     *
     * @param descriptor Descriptor of the message being tested.
     * @param fieldDescriptor FieldDescriptor being inspected for a direct match to the scope's
     *     definition.
     */
    abstract boolean matchesFieldDescriptor(Descriptor descriptor, FieldDescriptor fieldDescriptor);

    @Override
    boolean matchesFieldPath(Context context, Cache cache) {
      if (context.field().isPresent()
          && matchesFieldDescriptor(context.descriptor(), context.field().get())) {
        return true;
      }

      for (SpecificField field : context.fieldPath()) {
        FieldDescriptor specificFieldDescriptor = field.getField();
        if (specificFieldDescriptor != null
            && matchesFieldDescriptor(context.descriptor(), specificFieldDescriptor)) {
          return true;
        }
      }

      return false;
    }

    @Override
    boolean matchesContentOfSubMessages(Context context, Cache cache) {
      for (Message message : context.messageFields()) {
        if (messageHasMatchingField(context, cache, message)) {
          return true;
        }
      }
      return false;
    }

    private boolean messageHasMatchingField(Context context, Cache cache, Message message) {
      Map<Message, Boolean> messagesWithMatchingField = cache.getMessagesWithMatchingField(this);
      if (messagesWithMatchingField.containsKey(message)) {
        return messagesWithMatchingField.get(message);
      }

      boolean result = false;
      Map<FieldDescriptor, Object> fields = message.getAllFields();
      for (FieldDescriptor key : fields.keySet()) {
        if (matchesFieldDescriptor(context.descriptor(), key)) {
          result = true;
        } else if (key.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
          if (key.isRepeated()) {
            for (int i = 0; i < message.getRepeatedFieldCount(key); i++) {
              if (messageHasMatchingField(
                  context, cache, (Message) message.getRepeatedField(key, i))) {
                result = true;
                break;
              }
            }
          } else if (messageHasMatchingField(context, cache, (Message) message.getField(key))) {
            result = true;
          }
        }

        if (result) {
          break;
        }
      }

      messagesWithMatchingField.put(message, result);
      return result;
    }
  }

  // Matches any specific fields which fall under a sub-message field (or root) matching the root
  // message type and one of the specified field numbers.
  private static final class FieldNumbersScopeImpl extends FieldMatcherBaseScopeImpl {
    private final ImmutableSet<Integer> fieldNumbers;

    FieldNumbersScopeImpl(int... fieldNumbers) {
      this.fieldNumbers = ImmutableSet.copyOf(Ints.asList(fieldNumbers));
    }

    @Override
    void validate(Descriptor descriptor) {
      super.validate(descriptor);
      for (int fieldNumber : fieldNumbers) {
        checkArgument(
            descriptor.findFieldByNumber(fieldNumber) != null,
            "Message type %s has no field with number %s.",
            descriptor.getFullName(),
            fieldNumber);
      }
    }

    @Override
    boolean matchesFieldDescriptor(Descriptor descriptor, FieldDescriptor fieldDescriptor) {
      return fieldDescriptor.getContainingType() == descriptor
          && fieldNumbers.contains(fieldDescriptor.getNumber());
    }
  }

  // Matches any specific fields which fall under one of the specified FieldDescriptors.
  private static final class FieldDescriptorsScopeImpl extends FieldMatcherBaseScopeImpl {
    private final ImmutableSet<FieldDescriptor> fieldDescriptors;

    FieldDescriptorsScopeImpl(FieldDescriptor... fieldDescriptors) {
      this.fieldDescriptors = ImmutableSet.copyOf(fieldDescriptors);
    }

    @Override
    boolean matchesFieldDescriptor(Descriptor descriptor, FieldDescriptor fieldDescriptor) {
      return fieldDescriptors.contains(fieldDescriptor);
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // COMPOUND SUBTYPES
  //////////////////////////////////////////////////////////////////////////////////////////////////

  private abstract static class CompoundFieldScopeImpl extends FieldScopeImpl {
    final ImmutableList<FieldScopeImpl> elements;

    CompoundFieldScopeImpl(FieldScopeImpl singleElem) {
      elements = ImmutableList.of(singleElem);
    }

    CompoundFieldScopeImpl(FieldScopeImpl firstElem, FieldScopeImpl secondElem) {
      elements = ImmutableList.of(firstElem, secondElem);
    }

    @Override
    final boolean matchesContentOfSubMessages(Context context, Cache cache) {
      // We ignore compound logic for checking sub-messages, since we want to ensure we inspect
      // any sub-message which could match a FieldScopeImpl at a deeper path.
      for (FieldScopeImpl impl : elements) {
        if (impl.matchesContentOfSubMessages(context, cache)) {
          return true;
        }
      }
      return false;
    }

    @Override
    final void validate(Descriptor descriptor) {
      for (FieldScopeImpl elem : elements) {
        elem.validate(descriptor);
      }
    }
  }

  private static final class IntersectionScopeImpl extends CompoundFieldScopeImpl {
    IntersectionScopeImpl(FieldScopeImpl subject1, FieldScopeImpl subject2) {
      super(subject1, subject2);
    }

    @Override
    boolean matchesFieldPath(Context context, Cache cache) {
      return elements.get(0).matchesFieldPath(context, cache)
          && elements.get(1).matchesFieldPath(context, cache);
    }
  }

  private static final class UnionScopeImpl extends CompoundFieldScopeImpl {
    UnionScopeImpl(FieldScopeImpl subject1, FieldScopeImpl subject2) {
      super(subject1, subject2);
    }

    @Override
    boolean matchesFieldPath(Context context, Cache cache) {
      return elements.get(0).matchesFieldPath(context, cache)
          || elements.get(1).matchesFieldPath(context, cache);
    }
  }

  private static final class NegationScopeImpl extends CompoundFieldScopeImpl {
    NegationScopeImpl(FieldScopeImpl subject) {
      super(subject);
    }

    @Override
    boolean matchesFieldPath(Context context, Cache cache) {
      return !elements.get(0).matchesFieldPath(context, cache);
    }
  }

  static FieldScope and(FieldScope fieldScope1, FieldScope fieldScope2) {
    return new IntersectionScopeImpl((FieldScopeImpl) fieldScope1, (FieldScopeImpl) fieldScope2);
  }

  static FieldScope or(FieldScope fieldScope1, FieldScope fieldScope2) {
    return new UnionScopeImpl((FieldScopeImpl) fieldScope1, (FieldScopeImpl) fieldScope2);
  }

  static FieldScope not(FieldScope subject) {
    return new NegationScopeImpl((FieldScopeImpl) subject);
  }
}
