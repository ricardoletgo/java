package com.jsoniter;

import com.jsoniter.spi.*;

import java.io.IOException;
import java.util.*;

class ReflectionObjectDecoder implements Decoder {

    private static Object NOT_SET = new Object() {
        @Override
        public String toString() {
            return "NOT_SET";
        }
    };
    private Map<Slice, Binding> allBindings = new HashMap<Slice, Binding>();
    private String tempCacheKey;
    private String ctorArgsCacheKey;
    private int tempCount;
    private long expectedTracker;
    private int requiredIdx;
    private int tempIdx;
    private ClassDescriptor desc;

    public ReflectionObjectDecoder(Class clazz) {
        try {
            init(clazz);
        } catch (Exception e) {
            throw new JsonException(e);
        }
    }

    private final void init(Class clazz) throws Exception {
        ClassDescriptor desc = ExtensionManager.getClassDescriptor(clazz, true);
        for (Binding param : desc.ctor.parameters) {
            addBinding(clazz, param);
        }
        this.desc = desc;
        if (desc.ctor.ctor == null && desc.ctor.staticFactory == null) {
            throw new JsonException("no constructor for: " + desc.clazz);
        }
        for (Binding field : desc.fields) {
            addBinding(clazz, field);
        }
        for (SetterDescriptor setter : desc.setters) {
            for (Binding param : setter.parameters) {
                addBinding(clazz, param);
            }
        }
        if (requiredIdx > 63) {
            throw new JsonException("too many required properties to track");
        }
        expectedTracker = Long.MAX_VALUE >> (63 - requiredIdx);
        if (!desc.ctor.parameters.isEmpty() || !desc.setters.isEmpty()) {
            tempCount = tempIdx;
            tempCacheKey = "temp@" + clazz.getCanonicalName();
            ctorArgsCacheKey = "ctor@" + clazz.getCanonicalName();
        }
    }

    private void addBinding(Class clazz, final Binding binding) {
        if (binding.asMissingWhenNotPresent) {
            binding.mask = 1L << requiredIdx;
            requiredIdx++;
        }
        if (binding.asExtraWhenPresent) {
            binding.decoder = new Decoder() {
                @Override
                public Object decode(JsonIterator iter) throws IOException {
                    throw new JsonException("found should not present property: " + binding.name);
                }
            };
        }
        if (binding.decoder == null) {
            // the field decoder might be registered directly
            binding.decoder = ExtensionManager.getDecoder(binding.decoderCacheKey());
        }
        binding.idx = tempIdx;
        for (String fromName : binding.fromNames) {
            Slice slice = Slice.make(fromName);
            if (allBindings.containsKey(slice)) {
                throw new JsonException("name conflict found in " + clazz + ": " + fromName);
            }
            allBindings.put(slice, binding);
        }
        tempIdx++;
    }

    @Override
    public final Object decode(JsonIterator iter) throws IOException {
        try {
            if (desc.ctor.parameters.isEmpty()) {
                if (desc.setters.isEmpty()) {
                    return decodeWithOnlyFieldBinding(iter);
                } else {
                    return decodeWithSetterBinding(iter);
                }
            } else {
                return decodeWithCtorBinding(iter);
            }
        } catch (Exception e) {
            throw new JsonException(e);
        }
    }

    private final Object decodeWithOnlyFieldBinding(JsonIterator iter) throws Exception {
        if (iter.readNull()) {
            CodegenAccess.resetExistingObject(iter);
            return null;
        }
        Object obj = CodegenAccess.existingObject(iter) == null ? createNewObject() : CodegenAccess.resetExistingObject(iter);
        if (!CodegenAccess.readObjectStart(iter)) {
            if (requiredIdx > 0) {
                if (desc.onMissingProperties == null) {
                    throw new JsonException("missing required properties: " + collectMissingFields(0));
                } else {
                    setToBinding(obj, desc.onMissingProperties, collectMissingFields(0));
                }
            }
            return obj;
        }
        Map<String, Object> extra = null;
        long tracker = 0L;
        Slice fieldName = CodegenAccess.readObjectFieldAsSlice(iter);
        Binding binding = allBindings.get(fieldName);
        if (binding == null) {
            extra = onUnknownProperty(iter, fieldName, extra);
        } else {
            if (binding.asMissingWhenNotPresent) {
                tracker |= binding.mask;
            }
            setToBinding(obj, binding, decode(iter, obj, binding));
        }
        while (CodegenAccess.nextToken(iter) == ',') {
            fieldName = CodegenAccess.readObjectFieldAsSlice(iter);
            binding = allBindings.get(fieldName);
            if (binding == null) {
                extra = onUnknownProperty(iter, fieldName, extra);
            } else {
                if (binding.asMissingWhenNotPresent) {
                    tracker |= binding.mask;
                }
                setToBinding(obj, binding, decode(iter, obj, binding));
            }
        }
        if (tracker != expectedTracker) {
            if (desc.onMissingProperties == null) {
                throw new JsonException("missing required properties: " + collectMissingFields(tracker));
            } else {
                setToBinding(obj, desc.onMissingProperties, collectMissingFields(tracker));
            }
        }
        setExtra(obj, extra);
        return obj;
    }

    private void setToBinding(Object obj, Binding binding, Object value) throws Exception {
        if (binding.field != null) {
            binding.field.set(obj, value);
        } else {
            binding.setter.invoke(obj, value);
        }
    }

    private final Object decodeWithCtorBinding(JsonIterator iter) throws Exception {
        if (iter.readNull()) {
            CodegenAccess.resetExistingObject(iter);
            return null;
        }
        Object[] temp = (Object[]) iter.tempObjects.get(tempCacheKey);
        if (temp == null) {
            temp = new Object[tempCount];
            iter.tempObjects.put(tempCacheKey, temp);
        }
        Arrays.fill(temp, NOT_SET);
        if (!CodegenAccess.readObjectStart(iter)) {
            if (requiredIdx > 0) {
                throw new JsonException("missing required properties: " + collectMissingFields(0));
            }
            return createNewObject(iter, temp);
        }
        Map<String, Object> extra = null;
        long tracker = 0L;
        Slice fieldName = CodegenAccess.readObjectFieldAsSlice(iter);
        Binding binding = allBindings.get(fieldName);
        if (binding == null) {
            extra = onUnknownProperty(iter, fieldName, extra);
        } else {
            if (binding.asMissingWhenNotPresent) {
                tracker |= binding.mask;
            }
            temp[binding.idx] = decode(iter, binding);
        }
        while (CodegenAccess.nextToken(iter) == ',') {
            fieldName = CodegenAccess.readObjectFieldAsSlice(iter);
            binding = allBindings.get(fieldName);
            if (binding == null) {
                extra = onUnknownProperty(iter, fieldName, extra);
            } else {
                if (binding.asMissingWhenNotPresent) {
                    tracker |= binding.mask;
                }
                temp[binding.idx] = decode(iter, binding);
            }
        }
        if (tracker != expectedTracker) {
            throw new JsonException("missing required properties: " + collectMissingFields(tracker));
        }
        Object obj = createNewObject(iter, temp);
        setExtra(obj, extra);
        for (Binding field : desc.fields) {
            Object val = temp[field.idx];
            if (val != NOT_SET) {
                field.field.set(obj, val);
            }
        }
        applySetters(temp, obj);
        return obj;
    }

    private final Object decodeWithSetterBinding(JsonIterator iter) throws Exception {
        if (iter.readNull()) {
            CodegenAccess.resetExistingObject(iter);
            return null;
        }
        Object obj = createNewObject();
        if (!CodegenAccess.readObjectStart(iter)) {
            if (requiredIdx > 0) {
                if (desc.onMissingProperties == null) {
                    throw new JsonException("missing required properties: " + collectMissingFields(0));
                } else {
                    setToBinding(obj, desc.onMissingProperties, collectMissingFields(0));
                }
            }
            return obj;
        }
        Map<String, Object> extra = null;
        long tracker = 0L;
        Object[] temp = (Object[]) iter.tempObjects.get(tempCacheKey);
        if (temp == null) {
            temp = new Object[tempCount];
            iter.tempObjects.put(tempCacheKey, temp);
        }
        Arrays.fill(temp, NOT_SET);
        Slice fieldName = CodegenAccess.readObjectFieldAsSlice(iter);
        Binding binding = allBindings.get(fieldName);
        if (binding == null) {
            extra = onUnknownProperty(iter, fieldName, extra);
        } else {
            if (binding.asMissingWhenNotPresent) {
                tracker |= binding.mask;
            }
            if (canSetDirectly(binding)) {
                temp[binding.idx] = decode(iter, obj, binding);
            } else {
                setToBinding(obj, binding, decode(iter, obj, binding));
            }
        }
        while (CodegenAccess.nextToken(iter) == ',') {
            fieldName = CodegenAccess.readObjectFieldAsSlice(iter);
            binding = allBindings.get(fieldName);
            if (binding == null) {
                extra = onUnknownProperty(iter, fieldName, extra);
            } else {
                if (binding.asMissingWhenNotPresent) {
                    tracker |= binding.mask;
                }
                if (canSetDirectly(binding)) {
                    temp[binding.idx] = decode(iter, obj, binding);
                } else {
                    setToBinding(obj, binding, decode(iter, obj, binding));
                }
            }
        }
        if (tracker != expectedTracker) {
            if (desc.onMissingProperties == null) {
                throw new JsonException("missing required properties: " + collectMissingFields(tracker));
            } else {
                setToBinding(obj, desc.onMissingProperties, collectMissingFields(tracker));
            }
        }
        setExtra(obj, extra);
        applySetters(temp, obj);
        return obj;
    }

    private void setExtra(Object obj, Map<String, Object> extra) throws Exception {
        if (desc.onExtraProperties != null) {
            if (desc.onExtraProperties.valueType == Any.class) {
                setToBinding(obj, desc.onExtraProperties, new Any(extra));
            } else {
                setToBinding(obj, desc.onExtraProperties, extra);
            }
        }
    }

    private boolean canSetDirectly(Binding binding) {
        return binding.field == null && binding.setter == null;
    }

    private Object decode(JsonIterator iter, Binding binding) throws Exception {
        Object value;
        if (binding.decoder == null) {
            value = CodegenAccess.read(iter, binding.valueTypeLiteral);
        } else {
            value = binding.decoder.decode(iter);
        }
        return value;
    }

    private Object decode(JsonIterator iter, Object obj, Binding binding) throws Exception {
        if (binding.field != null) {
            CodegenAccess.setExistingObject(iter, binding.field.get(obj));
        }
        return decode(iter, binding);
    }

    private Map<String, Object> onUnknownProperty(JsonIterator iter, Slice fieldName, Map<String, Object> extra) throws IOException {
        if (desc.asExtraForUnknownProperties) {
            if (desc.onExtraProperties == null) {
                throw new JsonException("unknown property: " + fieldName.toString());
            } else {
                if (extra == null) {
                    extra = new HashMap<String, Object>();
                }
                extra.put(fieldName.toString(), iter.readAnyObject());
            }
        } else {
            iter.skip();
        }
        return extra;
    }

    private List<String> collectMissingFields(long tracker) {
        List<String> missingFields = new ArrayList<String>();
        for (Binding binding : allBindings.values()) {
            if (binding.asMissingWhenNotPresent) {
                long mask = binding.mask;
                CodegenAccess.addMissingField(missingFields, tracker, mask, binding.name);
            }
        }
        return missingFields;
    }

    private void applySetters(Object[] temp, Object obj) throws Exception {
        for (SetterDescriptor setter : desc.setters) {
            Object[] args = new Object[setter.parameters.size()];
            for (int i = 0; i < setter.parameters.size(); i++) {
                args[i] = temp[setter.parameters.get(i).idx];
            }
            setter.method.invoke(obj, args);
        }
    }

    private Object createNewObject(JsonIterator iter, Object[] temp) throws Exception {
        Object[] ctorArgs = (Object[]) iter.tempObjects.get(ctorArgsCacheKey);
        if (ctorArgs == null) {
            ctorArgs = new Object[desc.ctor.parameters.size()];
            iter.tempObjects.put(ctorArgsCacheKey, ctorArgs);
        }
        Arrays.fill(ctorArgs, null);
        for (int i = 0; i < desc.ctor.parameters.size(); i++) {
            Object arg = temp[desc.ctor.parameters.get(i).idx];
            if (arg != NOT_SET) {
                ctorArgs[i] = arg;
            }
        }
        return createNewObject(ctorArgs);
    }

    private Object createNewObject(Object... args) throws Exception {
        if (desc.ctor.staticFactory != null) {
            return desc.ctor.staticFactory.invoke(null, args);
        } else {
            return desc.ctor.ctor.newInstance(args);
        }
    }
}