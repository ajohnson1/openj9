/*******************************************************************************
 * Copyright (c) 2021, 2022 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/
package org.openj9.test.jep424.downcall;

import org.testng.annotations.Test;
import org.testng.Assert;
import org.testng.AssertJUnit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import java.lang.foreign.Addressable;
import java.lang.foreign.Linker;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemorySession;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import static java.lang.foreign.ValueLayout.*;

/**
 * Test cases for JEP 424: Foreign Linker API (Preview) for primitive types in downcall, which
 * verifies the downcalls with the shared downcall handlder (cached as soft reference in OpenJDK)
 * in multithreading.
 */
@Test(groups = { "level.sanity" })
public class MultiThreadingTests2 implements Thread.UncaughtExceptionHandler {
	private volatile Throwable initException;

	static {
		System.loadLibrary("clinkerffitests");
	}
	private static final GroupLayout structLayout = MemoryLayout.structLayout(JAVA_INT.withName("elem1"), JAVA_INT.withName("elem2"));
	private static final FunctionDescriptor fd = FunctionDescriptor.of(structLayout, structLayout, structLayout);
	private static final Addressable functionSymbol = SymbolLookup.loaderLookup().lookup("add2IntStructs_returnStruct").get();
	private static final MethodHandle mh = Linker.nativeLinker().downcallHandle(functionSymbol, fd);

	@Test(enabled=false)
	@Override
	public void uncaughtException(Thread thr, Throwable t) {
		initException =  t;
	}

	@Test
	public void test_twoThreadsWithSameFuncDesc_SharedDowncallHandler() throws Throwable {
		Thread thr1 = new Thread(){
			public void run() {
				try {
					VarHandle intHandle1 = structLayout.varHandle(PathElement.groupElement("elem1"));
					VarHandle intHandle2 = structLayout.varHandle(PathElement.groupElement("elem2"));

					try (MemorySession session = MemorySession.openConfined()) {
						SegmentAllocator allocator = SegmentAllocator.newNativeArena(session);
						MemorySegment structSegmt1 = allocator.allocate(structLayout);
						intHandle1.set(structSegmt1, 11223344);
						intHandle2.set(structSegmt1, 55667788);
						MemorySegment structSegmt2 = allocator.allocate(structLayout);
						intHandle1.set(structSegmt2, 99001122);
						intHandle2.set(structSegmt2, 33445566);

						MemorySegment resultSegmt = (MemorySegment)mh.invokeExact(allocator, structSegmt1, structSegmt2);
						Assert.assertEquals(intHandle1.get(resultSegmt), 110224466);
						Assert.assertEquals(intHandle2.get(resultSegmt), 89113354);
					}
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}
		};

		Thread thr2 = new Thread(){
			public void run() {
				try {
					VarHandle intHandle1 = structLayout.varHandle(PathElement.groupElement("elem1"));
					VarHandle intHandle2 = structLayout.varHandle(PathElement.groupElement("elem2"));

					try (MemorySession session = MemorySession.openConfined()) {
						SegmentAllocator allocator = SegmentAllocator.newNativeArena(session);
						MemorySegment structSegmt1 = allocator.allocate(structLayout);
						intHandle1.set(structSegmt1, 11223344);
						intHandle2.set(structSegmt1, 55667788);
						MemorySegment structSegmt2 = allocator.allocate(structLayout);
						intHandle1.set(structSegmt2, 99001123);
						intHandle2.set(structSegmt2, 33445567);

						MemorySegment resultSegmt = (MemorySegment)mh.invokeExact(allocator, structSegmt1, structSegmt2);
						Assert.assertEquals(intHandle1.get(resultSegmt), 110224467);
						Assert.assertEquals(intHandle2.get(resultSegmt), 89113355);
					}
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}
		};

		thr1.setUncaughtExceptionHandler(this);
		thr2.setUncaughtExceptionHandler(this);

		thr1.start();
		thr2.start();

		thr1.join();
		thr2.join();

		if (initException != null){
			throw new RuntimeException(initException);
		}
	}
}
