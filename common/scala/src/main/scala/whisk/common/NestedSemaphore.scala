/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import java.util.concurrent.Semaphore
import scala.collection.concurrent.TrieMap

class NestedSemaphore[T](memoryPermits: Int) extends ForcibleSemaphore(memoryPermits) {
  private val actionLocks = TrieMap.empty[String, Semaphore]
  private val concurrentSlotsMap = TrieMap.empty[String, ResizableSemaphore] //one key per action; resized per container

//  @tailrec
  final def tryAcquire(actionid: T, maxConcurrent: Int, memoryPermits: Int, retries: Int): Boolean = {
    val concurrentSlots = concurrentSlotsMap
      .getOrElseUpdate(actionid.toString, new ResizableSemaphore(0, maxConcurrent))

    if (maxConcurrent == 1) {
      super.tryAcquire(memoryPermits)
    } else {
      if (concurrentSlots.tryAcquire(1)) {
        true
      } else {

//      concurrentSlots.synchronized {
//        if (concurrentSlots.tryAcquire(1)) {
//          true
//        } else if (super.tryAcquireOnce(memoryPermits)) {
//          concurrentSlots.release(maxConcurrent - 1, false)
//          true
//        } else {
//          false
//        }
//      }

        //without synchonized:
        val actionMutex = actionLocks.getOrElseUpdate(actionid.toString, {
          new Semaphore(1)
        })

        if (actionMutex.tryAcquire()) {
          //double check concurrentSlots, then attempt memory aquire for new container, then false
          val result = if (concurrentSlots.tryAcquire(1)) {
            true
          } else if (super.tryAcquire(memoryPermits)) {
            concurrentSlots.release(maxConcurrent - 1, false)
            true
          } else {
            false
          }
          actionMutex.release(1)
          result
        } else {
          tryAcquire(actionid, maxConcurrent, memoryPermits, retries + 1)
        }

      }
    }
  }
  def forceAcquire(actionid: T, maxConcurrent: Int, memoryPermits: Int): Unit = {
    require(memoryPermits > 0, "cannot force acquire negative or no permits")

    if (maxConcurrent > 1) {
      val concurrentSlots = concurrentSlotsMap
        .getOrElseUpdate(actionid.toString, new ResizableSemaphore(0, maxConcurrent))
      sync.forceAquireShared(memoryPermits)
      concurrentSlots.release(maxConcurrent - 1, false)

    } else {
      super.forceAcquire(memoryPermits)
    }
  }

  /**
   * Releases the given amount of permits
   *
   * @param acquires the number of permits to release
   */
  def release(actionid: T, maxConcurrent: Int, memoryPermits: Int): Unit = {
    require(memoryPermits > 0, "cannot release negative or no permits")
    val concurrentSlots = concurrentSlotsMap
      .getOrElseUpdate(actionid.toString, new ResizableSemaphore(0, maxConcurrent))

    if (concurrentSlots.release(1, true)) {
      sync.releaseShared(memoryPermits)
    }
  }
  def concurrentState = concurrentSlotsMap
}
