/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.runtime.evaluators.constructors;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.asterix.formats.nontagged.BinaryComparatorFactoryProvider;
import org.apache.asterix.formats.nontagged.SerializerDeserializerProvider;
import org.apache.asterix.om.base.AFloat;
import org.apache.asterix.om.base.AMutableFloat;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.functions.IFunctionDescriptor;
import org.apache.asterix.om.functions.IFunctionDescriptorFactory;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import org.apache.asterix.runtime.exceptions.InvalidDataFormatException;
import org.apache.asterix.runtime.exceptions.TypeMismatchException;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.UTF8StringPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;
import org.apache.hyracks.util.string.UTF8StringUtil;

public class AFloatConstructorDescriptor extends AbstractScalarFunctionDynamicDescriptor {
    private static final long serialVersionUID = 1L;
    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        @Override
        public IFunctionDescriptor createFunctionDescriptor() {
            return new AFloatConstructorDescriptor();
        }
    };

    @Override
    public IScalarEvaluatorFactory createEvaluatorFactory(final IScalarEvaluatorFactory[] args) {
        return new IScalarEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IScalarEvaluator createScalarEvaluator(IHyracksTaskContext ctx) throws HyracksDataException {
                return new IScalarEvaluator() {
                    private ArrayBackedValueStorage resultStorage = new ArrayBackedValueStorage();
                    private DataOutput out = resultStorage.getDataOutput();
                    private IPointable inputArg = new VoidPointable();
                    private IScalarEvaluator eval = args[0].createScalarEvaluator(ctx);
                    private final byte[] POSITIVE_INF = UTF8StringUtil.writeStringToBytes("INF");
                    private final byte[] NEGATIVE_INF = UTF8StringUtil.writeStringToBytes("-INF");
                    private final byte[] NAN = UTF8StringUtil.writeStringToBytes("NaN");
                    private IBinaryComparator utf8BinaryComparator =
                            BinaryComparatorFactoryProvider.UTF8STRING_POINTABLE_INSTANCE.createBinaryComparator();
                    private AMutableFloat aFloat = new AMutableFloat(0);
                    @SuppressWarnings("unchecked")
                    private ISerializerDeserializer<AFloat> floatSerde =
                            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AFLOAT);
                    private final UTF8StringPointable utf8Ptr = new UTF8StringPointable();

                    @Override
                    public void evaluate(IFrameTupleReference tuple, IPointable result) throws HyracksDataException {
                        try {
                            eval.evaluate(tuple, inputArg);
                            byte[] serString = inputArg.getByteArray();
                            int offset = inputArg.getStartOffset();
                            int len = inputArg.getLength();

                            byte tt = serString[offset];
                            if (tt == ATypeTag.SERIALIZED_FLOAT_TYPE_TAG) {
                                result.set(inputArg);
                            } else if (tt == ATypeTag.SERIALIZED_STRING_TYPE_TAG) {
                                resultStorage.reset();
                                if (utf8BinaryComparator.compare(serString, offset + 1, len - 1, POSITIVE_INF, 0,
                                        5) == 0) {
                                    aFloat.setValue(Float.POSITIVE_INFINITY);
                                } else if (utf8BinaryComparator.compare(serString, offset + 1, len - 1, NEGATIVE_INF, 0,
                                        6) == 0) {
                                    aFloat.setValue(Float.NEGATIVE_INFINITY);
                                } else if (utf8BinaryComparator.compare(serString, offset + 1, len - 1, NAN, 0,
                                        5) == 0) {
                                    aFloat.setValue(Float.NaN);
                                } else {
                                    utf8Ptr.set(serString, offset + 1, len - 1);
                                    aFloat.setValue(Float.parseFloat(utf8Ptr.toString()));
                                }
                                floatSerde.serialize(aFloat, out);
                                result.set(resultStorage);
                            } else {
                                throw new TypeMismatchException(getIdentifier(), 0, tt,
                                        ATypeTag.SERIALIZED_STRING_TYPE_TAG);
                            }
                        } catch (IOException e) {
                            throw new InvalidDataFormatException(getIdentifier(), e,
                                    ATypeTag.SERIALIZED_FLOAT_TYPE_TAG);
                        }
                    }
                };
            }
        };
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return BuiltinFunctions.FLOAT_CONSTRUCTOR;
    }
}
