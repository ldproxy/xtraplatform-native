/*
 * Copyright 2026 interactive instruments GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package de.ii.xtraplatform.xtralink.domain

import de.ii.xtraplatform.blobs.domain.ResourceStore
import spock.lang.Specification

import java.nio.file.Path
import java.util.stream.Stream

class JobProcessorSimpleSpec extends Specification {

    ResourceStore jobStore = Mock()

    JobProcessorSimple processor() {
        ResourceStore resourceStore = Stub() {
            with(*_) >> jobStore
        }
        return Spy(JobProcessorSimple, constructorArgs: ["test", resourceStore])
    }

    def "no store writes when no job of an earlier run left output files"() {
        given:
        def processor = processor()

        when:
        processor.beforeQueueStart(false)

        then:
        1 * jobStore.walk(Path.of(".outputs"), _, _) >> Stream.empty()
        0 * jobStore.delete(_)
        0 * jobStore.put(_, _)
    }

    def "output files of an earlier run are deleted together with their markers"() {
        given:
        def processor = processor()

        when:
        processor.beforeQueueStart(false)

        then:
        2 * jobStore.walk(Path.of(".outputs"), _, _) >>> [Stream.of(Path.of("job1/results/a.json")), Stream.empty()]
        1 * jobStore.delete(Path.of("results/a.json"))
        1 * jobStore.delete(Path.of(".outputs/job1/results/a.json"))
        1 * jobStore.delete(Path.of(".outputs"))
    }

    def "nothing is deleted with a persistent queue"() {
        given:
        def processor = processor()

        when:
        processor.beforeQueueStart(true)

        then:
        0 * jobStore._
    }
}
