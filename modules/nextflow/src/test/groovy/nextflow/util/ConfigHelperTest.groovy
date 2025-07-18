/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.util

import java.nio.file.Files
import java.nio.file.Paths

import nextflow.SysEnv
import nextflow.config.ConfigClosurePlaceholder
import spock.lang.Specification
import spock.lang.Unroll

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ConfigHelperTest extends Specification {

    @Unroll
    def "get config property" () {

        expect:
        ConfigHelper.getConfigProperty(config, execName, 'foo') == value

        where:
        config               | execName | value
        [foo: 0]             | null     | 0
        [foo: 100]           | null     | 100
        [foo: 'bar']         | null     | 'bar'
        [$sge: [foo: 'bar']] | 'sge'    | 'bar'

    }

    @Unroll
    def "should parse string value: #str" () {

        expect:
        ConfigHelper.parseValue(str) == value

        where:
        str         | value
        'hola'      | 'hola'
        '1'         | 1
        "${Long.MAX_VALUE}" | Long.MAX_VALUE
        'True'      | true
        'False'     | false
        "10.2"      | 10.2
        '5sec'      | Duration.of('5sec')
        'live_in_3d'| 'live_in_3d'

    }

    def testResolveClasspaths() {

        given:
        def path1 = Files.createTempDirectory('path1')
        path1.resolve('file1').text = 'File 1'
        path1.resolve('file2.jar').text = 'File 2'
        path1.resolve('dir').mkdir()
        path1.resolve('dir/file3').text = 'File 3'
        path1.resolve('dir/file4').text = 'File 4'

        def path2 = Files.createTempDirectory('path2')
        path2.resolve('file5').text = 'File 5'
        path2.resolve('file6.jar').text = 'File 6'

        def path3 = Paths.get('/some/file')

        when:
        def list = ConfigHelper.resolveClassPaths([path1, path2, path3])
        then:
        list.size() == 4
        list.contains( path1 )
        list.contains( path1.resolve('file2.jar') )
        list.contains( path2 )
        list.contains( path2.resolve('file6.jar') )

        cleanup:
        path1?.deleteDir()
        path2?.deleteDir()

    }

    def 'should render config using properties notation' () {

        given:
        def mem = { return 0 }
        def config = new ConfigObject()
        config.docker.enabled = true
        config.process.executor = 'slurm'
        config.process.queue = 'long'
        config.process.memory = mem
        config.process.omega = "Hi' there"

        when:
        def result = ConfigHelper.toPropertiesString(config,true)

        then:
        result == """
                docker.enabled=true
                process.executor=slurm
                process.memory=${mem.toString()}
                process.omega=Hi' there
                process.queue=long
                """
                .stripIndent().leftTrim()

    }
    def 'should render config using canonical notation' () {

        given:
        def config = new ConfigObject()
        config.process.executor = 'slurm'
        config.process.queue = 'long'
        config.docker.enabled = true
        config.dummy = new ConfigObject() // <-- empty config object should not be print
        config.mail.from = 'yo@mail.com'
        config.mail.smtp.port = 25
        config.mail.smtp.user = 'yo'
        config.mail.smtp.host = 'mail.com'

        when:
        def result = ConfigHelper.toCanonicalString(config)
        then:
        result == '''
                    process {
                       executor = 'slurm'
                       queue = 'long'
                    }
                    
                    docker {
                       enabled = true
                    }

                    mail {
                       from = 'yo@mail.com'
                       smtp {
                          port = 25
                          user = 'yo'
                          host = 'mail.com'
                       }
                    }
                    '''
                .stripIndent().leftTrim()


        when:
        result = ConfigHelper.toCanonicalString(config, true)
        then:
        result == '''
                    docker {
                       enabled = true
                    }
                    
                    mail {
                       from = 'yo@mail.com'
                       smtp {
                          host = 'mail.com'
                          port = 25
                          user = 'yo'
                       }
                    }

                    process {
                       executor = 'slurm'
                       queue = 'long'
                    }
                    '''
                .stripIndent().leftTrim()

    }

    def 'should quote special identifiers' () {

        given:
        def config = new ConfigObject()
        config.'alpha-bravo' = 1
        config.'fizz+buzz'.x = 'hello'
        config.'fizz+buzz'.y = 'world'

        when:
        def result = ConfigHelper.toCanonicalString(config)
        then:
        result == '''
                    'alpha-bravo' = 1

                    'fizz+buzz' {
                       x = 'hello'
                       y = 'world'
                    }
                    '''
                    .stripIndent().leftTrim()
    }

    def 'should render config using flatten notation' () {

        given:
        def config = new ConfigObject()
        config.process.queue = 'long'
        config.process.executor = 'slurm'
        config.docker.enabled = true
        config.zeta.'quoted-attribute'.foo = 1

        when:
        def result = ConfigHelper.toFlattenString(config)
        then:
        result == '''
                process.queue = 'long'
                process.executor = 'slurm'
                docker.enabled = true
                zeta.'quoted-attribute'.foo = 1
                '''
                .stripIndent().leftTrim()

        when:
        result = ConfigHelper.toFlattenString(config, true)
        then:
        result == '''
                docker.enabled = true
                process.executor = 'slurm'
                process.queue = 'long'
                zeta.'quoted-attribute'.foo = 1
                '''
                .stripIndent().leftTrim()

        when:
        config = new ConfigObject()
        config.foo = "Hi' there"

        result = ConfigHelper.toFlattenString(config)
        then:
        result == "foo = 'Hi\\' there'\n"

    }

    def 'should render config as json' () {
        given:
        def config = new ConfigObject()
        config.process.queue = 'long'
        config.process.executor = 'slurm'
        config.process.memory = new ConfigClosurePlaceholder('{ 1.GB }')
        config.docker.enabled = true
        config.zeta.'quoted-attribute'.foo = 1

        when:
        def result = ConfigHelper.toJsonString(config, true)
        then:
        result == '''
            {
                "docker": {
                    "enabled": true
                },
                "process": {
                    "executor": "slurm",
                    "memory": "{ 1.GB }",
                    "queue": "long"
                },
                "zeta": {
                    "quoted-attribute": {
                        "foo": 1
                    }
                }
            }
            '''.stripIndent().trim()


        when:
        result = ConfigHelper.toJsonString(config, false)
        then:
        result == '''
            {
                "process": {
                    "queue": "long",
                    "executor": "slurm",
                    "memory": "{ 1.GB }"
                },
                "docker": {
                    "enabled": true
                },
                "zeta": {
                    "quoted-attribute": {
                        "foo": 1
                    }
                }
            }
            '''.stripIndent().trim()
    }

    def 'should render config as yaml' () {
        given:
        def config = new ConfigObject()
        config.process.queue = 'long'
        config.process.executor = 'slurm'
        config.process.memory = new ConfigClosurePlaceholder('{ 1.GB }')
        config.docker.enabled = true
        config.zeta.'quoted-attribute'.foo = 1

        when:
        def result = ConfigHelper.toYamlString(config, true)
        then:
        result == '''\
            docker:
              enabled: true
            process:
              executor: slurm
              memory: '{ 1.GB }'
              queue: long
            zeta:
              quoted-attribute:
                foo: 1
            '''.stripIndent()


        when:
        result = ConfigHelper.toYamlString(config, false)
        then:
        result == '''\
            process:
              queue: long
              executor: slurm
              memory: '{ 1.GB }'
            docker:
              enabled: true
            zeta:
              quoted-attribute:
                foo: 1
            '''.stripIndent()
    }

    def 'should verify valid identifiers' () {

        expect:
        ConfigHelper.isValidIdentifier('foo')
        ConfigHelper.isValidIdentifier('foo_bar')
        !ConfigHelper.isValidIdentifier('foo-bar')
        !ConfigHelper.isValidIdentifier('foo+bar')
        !ConfigHelper.isValidIdentifier('0foo')
    }

    @Unroll
    def 'should wrap names with key #str' () {
        expect:
        ConfigHelper.wrap0(str) == expected0
        ConfigHelper.wrap1(str) == expected1

        where:
        str                 | expected0             | expected1
        "foo"               | "foo"                 | 'foo'
        "foo-1"             | "'foo-1'"             | "'foo-1'"

        "withLabel:foo"     | "'withLabel:foo'"     | "withLabel:foo"
        "withLabel:1foo"    | "'withLabel:1foo'"    | "withLabel:'1foo'"

        "withName:foo"      | "'withName:foo'"      | "withName:foo"
        "withName:2foo"     | "'withName:2foo'"     | "withName:'2foo'"
    }

    @Unroll
    def 'should get config from map' () {
        given:
        def NAME = 'foo'
        def PREFIX = 'P_'
        and:
        SysEnv.push(ENV)

        expect:
        ConfigHelper.valueOf(CONFIG, NAME, PREFIX, DEF_VAL, DEF_TYPE) == EXPECTED

        cleanup:
        SysEnv.pop()

        where:
        CONFIG          | ENV           | DEF_VAL       | DEF_TYPE      | EXPECTED
        null            | [:]           | null          | String        | null
        [:]             | [:]           | null          | String        | null
        [:]             | [:]           | 'one'         | String        | 'one'
        [foo:'two']     | [:]           | 'one'         | String        | 'two'
        [foo:'']        | [:]           | 'one'         | String        | ''
        [foo:'two']     | [P_FOO:'bar'] | 'one'         | String        | 'two'
        [:]             | [P_FOO:'bar'] | 'one'         | String        | 'bar'

        and:
        null            | [:]           | null          | Integer       | null
        [:]             | [:]           | null          | Integer       | null
        [:]             | [:]           | 1             | Integer       | 1
        [foo:2]         | [:]           | 1             | Integer       | 2
        [foo:'2']       | [:]           | 1             | Integer       | 2
        [foo:'2']       | [P_FOO:'3']   | 1             | Integer       | 2
        [:]             | [P_FOO:'3']   | 1             | Integer       | 3

        and:
        null            | [:]           | null          | Boolean       | null
        [:]             | [:]           | true          | Boolean       | true
        [foo:false]     | [:]           | true          | Boolean       | false
        [foo:'false']   | [:]           | true          | Boolean       | false
        [foo:true]      | [:]           | false         | Boolean       | true
        [foo:'true']    | [:]           | false         | Boolean       | true
        [foo:'true']    | [P_FOO:'false']| null         | Boolean       | true
        [:]             | [P_FOO:'false']| null         | Boolean       | false
        [:]             | [P_FOO:'true'] | null         | Boolean       | true

        and:
        [:]             | [:]           | Duration.of('1s') | Duration  | Duration.of('1s')
        [foo:'10ms']    | [:]           | null              | Duration  | Duration.of('10ms')
        [:]             | [P_FOO:'1s']  | null              | Duration  | Duration.of('1s')
    }

    def 'should map camelCase to snake uppercase' () {
        given:
        SysEnv.push(ENV)

        expect:
        ConfigHelper.valueOf([:], NAME, PREFIX, null, String) == EXPECTED

        cleanup:
        SysEnv.pop()

        where:
        EXPECTED    | PREFIX    | NAME              | ENV
        null        | 'foo'     | 'bar'             | [:]
        'one'       | 'foo'     | 'bar'             | [FOO_BAR: 'one']
        'one'       | 'foo_'    | 'bar'             | [FOO_BAR: 'one']
        'one'       | 'foo_'    | 'thisAndThat'     | [FOO_THIS_AND_THAT: 'one']
    }

}
