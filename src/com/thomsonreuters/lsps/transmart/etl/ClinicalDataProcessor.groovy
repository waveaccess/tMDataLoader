/*************************************************************************
 * tranSMART Data Loader - ETL tool for tranSMART
 *
 * Copyright 2012-2013 Thomson Reuters
 *
 * This product includes software developed at Thomson Reuters
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License 
 * as published by the Free Software  
 * Foundation, either version 3 of the License, or (at your option) any later version, along with the following terms:
 * 1.	You may convey a work based on this program in accordance with section 5, provided that you retain the above notices.
 * 2.	You may convey verbatim copies of this program code as you receive it, in any medium, provided that you retain the above notices.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS    * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 ******************************************************************/

package com.thomsonreuters.lsps.transmart.etl

import java.io.File
import groovy.sql.Sql

class ClinicalDataProcessor extends DataProcessor {

    public ClinicalDataProcessor(Object conf) {
        super(conf);
    }

    private long processEachRow(File f, fMappings, Closure<List> processRow) {
        def lineNum = 0L
        f.splitEachLine("\t") {
            def cols = [''] // to support 0-index properly (we use it for empty data values)
            cols.addAll(it)

            lineNum++
            if (lineNum < 2) return; // skipping header

            if (cols[fMappings['STUDY_ID']]) {
                // the line shouldn't be empty

                if (!cols[fMappings['SUBJ_ID']]) {
                    throw new Exception("SUBJ_ID are not defined at line ${lineNum}")
                }

                def output = [
                        study_id: cols[fMappings['STUDY_ID']],
                        site_id: cols[fMappings['SITE_ID']],
                        subj_id: cols[fMappings['SUBJ_ID']],
                        visit_name: cols[fMappings['VISIT_NAME']],
                        data_label: '', // DATA_LABEL
                        data_value: '', // DATA_VALUE
                        category_cd: '', // CATEGORY_CD
                        ctrl_vocab_code: ''  // CTRL_VOCAB_CODE - unused
                ]

                if (fMappings['_DATA']) {
                    fMappings['_DATA'].each {
                        v ->

                            def out = output.clone()

                            out['data_value'] = fixColumn(cols[v['COLUMN']])
                            def cat_cd = v['CATEGORY_CD']

                            if (v['DATA_LABEL_SOURCE'] > 0) {
                                // ok, the actual data label is in the referenced column
                                out['data_label'] = fixColumn(cols[v['DATA_LABEL_SOURCE']])
                                // now need to modify CATEGORY_CD before proceeding

                                // handling DATALABEL in category_cd
                                if (!cat_cd.contains('DATALABEL')) {
                                    // do this only if category_cd doesn't contain DATALABEL yet
                                    if (v['DATA_LABEL_SOURCE_TYPE'] == 'A')
                                        cat_cd = (cat_cd =~ /^(.+)\+([^\+]+?)$/).replaceFirst('$1+DATALABEL+$2')
                                    else
                                        cat_cd = cat_cd + '+DATALABEL'
                                }

                            } else {
                                out['data_label'] = fixColumn(v['DATA_LABEL'])
                            }

                            // VISIT_NAME special handling; do it only when VISITNAME is not in category_cd already
                            if (!(cat_cd.contains('VISITNAME') || cat_cd.contains('+VISITNFST'))) {
                                if (config.visitNameFirst) {
                                    cat_cd = cat_cd + '+VISITNFST'
                                }
                            }

                            out['category_cd'] = fixColumn(cat_cd)

                            processRow(out)
                    }
                } else {
                    processRow(output)
                }
            }
        }
        return lineNum
    }

    @Override
    public boolean processFiles(File dir, Sql sql, studyInfo) {
        // read mapping file first
        // then parse files that are specified there (to allow multiple files per study)

        sql.execute('TRUNCATE TABLE tm_lz.lt_src_clinical_data')

        dir.eachFileMatch(~/(?i).+_Mapping_File\.txt/) {
            def mappings = processMappingFile(it)

            if (mappings.size() <= 0) {
                config.logger.log(LogType.ERROR, "Empty mappings file!")
                throw new Exception("Empty mapping file")
            }

            mappings.each {
                fName, fMappings ->
                    this.processFile(sql, new File(dir, fName), fMappings)
            }
        }

        return trySetStudyId(sql, studyInfo)
    }

    private void processFile(sql, f, fMappings) {
        config.logger.log("Processing ${f.getName()}")
        if (!f.exists()) {
            config.logger.log("File ${f.getName()} doesn't exist!")
            throw new Exception("File ${f.getName()} doesn't exist")
        }

        if (isLocalPostgresConnection()) {
            processFileForLocalPostgres(sql, f, fMappings)
        } else {
            processFileForGenericDatabase(sql, f, fMappings)
        }
    }

    private void processFileForLocalPostgres(sql, f, fMappings) {
        def tempCsv = File.createTempFile("clinicalData", ".csv")
        def lineNum = 0
        tempCsv.withPrintWriter {
            writer ->
                lineNum = processEachRow f, fMappings, {
                    row ->
                        writer
                                .append(row.study_id).append('\t')
                                .append(row.site_id).append('\t')
                                .append(row.subj_id).append('\t')
                                .append(row.visit_name).append('\t')
                                .append(row.data_label).append('\t')
                                .append(row.data_value).append('\t')
                                .append(row.category_cd).append("\n")
                }
        }
        config.logger.log("Loading ${lineNum} rows into database")
        sql.execute("""
            COPY
                tm_lz.lt_src_clinical_data(
                    STUDY_ID, SITE_ID, SUBJECT_ID,
                    VISIT_NAME, DATA_LABEL, DATA_VALUE, CATEGORY_CD
                )
            FROM '${tempCsv.getCanonicalPath()}'
        """.toString())
        tempCsv.delete()
        config.logger.log("Processed ${lineNum} rows")
    }

    private void processFileForGenericDatabase(sql, f, fMappings) {
        def lineNum = 0

        sql.withTransaction {
            sql.withBatch(100, """\
					INSERT into tm_lz.lt_src_clinical_data
										(STUDY_ID, SITE_ID, SUBJECT_ID, VISIT_NAME, DATA_LABEL, DATA_VALUE, CATEGORY_CD)
									VALUES (:study_id, :site_id, :subj_id, :visit_name,
										:data_label, :data_value, :category_cd)
					""") {
                stmt ->
                    lineNum = processEachRow f, fMappings, {
                        stmt.addBatch(it)
                    }
            }
        }
        sql.commit() // TODO: do we need it here?
        config.logger.log("Processed ${lineNum} rows")
    }

    private boolean trySetStudyId(Sql sql, studyInfo) {
// OK, now we need to retrieve studyID & node
        def rows = sql.rows("select study_id, count(*) as cnt from tm_lz.lt_src_clinical_data group by study_id")
        def rsize = rows.size()

        if (rsize > 0) {
            if (rsize == 1) {
                def studyId = rows[0].study_id
                if (studyId) {
                    studyInfo['id'] = studyId
                } else {
                    config.logger.log(LogType.ERROR, "Study ID is null!")
                    return false
                }
            } else {
                config.logger.log(LogType.ERROR, "Multiple StudyIDs are detected!")
                return false
            }
        } else {
            config.logger.log(LogType.ERROR, "Study ID is not specified!")
            return false
        }

        return true;
    }

    @Override
    public String getProcedureName() {
        return config.altClinicalProcName ?: "I2B2_LOAD_CLINICAL_DATA"
    }

    @Override
    public boolean runStoredProcedures(jobId, Sql sql, studyInfo) {
        def studyId = studyInfo['id']
        def studyNode = studyInfo['node']
        if (studyId && studyNode) {
            config.logger.log("Study ID=${studyId}; Node=${studyNode}")
			sql.call("{call " + config.controlSchema + "." + getProcedureName() + "(?,?,?,?,?)}", [ studyId, studyNode, config.securitySymbol, 'N', jobId ])
            //sql.rows("SELECT tm_cz.i2b2_load_clinical_data(?,?,?,?,?)", [ studyId, studyNode, config.securitySymbol, 'N', jobId ])
        } else {
            config.logger.log(LogType.ERROR, "Study ID or Node not defined!")
            return false;
        }

        return true;
    }

    private Object processMappingFile(f) {
        def mappings = [:]

        config.logger.log("Mapping file: ${f.name}")

        def lineNum = 0

        f.splitEachLine("\t") {
            cols ->

                lineNum++

                if (cols[0] && lineNum > 1) {
                    if (!mappings[cols[0]]) {
                        mappings[cols[0]] = [
                                STUDY_ID: 0,
                                SITE_ID: 0,
                                SUBJ_ID: 0,
                                VISIT_NAME: 0,
                                _DATA: []
                                // [ { DATA_LABEL_SOURCE => 1, DATA_LABEL_SOURCE_TYPE => 'A',
                                // DATA_LABEL => Label, CATEGORY_CD => '', COLUMN => 1 } ] - 1-based column numbers
                        ];
                    }

                    def curMapping = mappings[cols[0]]

                    def dataLabel = cols[3]
                    if (dataLabel != 'OMIT' && dataLabel != 'DATA_LABEL') {
                        if (dataLabel == '\\') {
                            // the actual data label should be taken from a specified column [4]
                            def dataLabelSource = 0
                            def dataLabelSourceType = ''

                            def m = cols[4] =~ /^(\d+)(A|B){0,1}$/
                            if (m.size() > 0) {
                                dataLabelSource = m[0][1].toInteger()
                                dataLabelSourceType = (m[0][2] in ['A', 'B']) ? m[0][2] : 'A'
                            }

                            if (cols[1] && cols[2].toInteger() > 0 && dataLabelSource > 0) {
                                curMapping['_DATA'].add([
                                        CATEGORY_CD: cols[1],
                                        COLUMN: cols[2].toInteger(),
                                        DATA_LABEL_SOURCE: dataLabelSource,
                                        DATA_LABEL_SOURCE_TYPE: dataLabelSourceType
                                ])
                            }
                        } else {
                            if (curMapping.containsKey(dataLabel)) {
                                curMapping[dataLabel] = cols[2].toInteger()
                            } else {
                                if (cols[1] && cols[2].toInteger() > 0) {
                                    curMapping['_DATA'].add([
                                            DATA_LABEL: dataLabel,
                                            CATEGORY_CD: cols[1],
                                            COLUMN: cols[2].toInteger()
                                    ])
                                } else {
                                    config.logger.log(LogType.ERROR, "Category or column number is missing for line ${lineNum}")
                                    throw new Exception("Error parsing mapping file")
                                }
                            }
                        }
                    }
                }
        }

        return mappings
    }

    private String fixColumn(String s) {
        if (s == null) return '';

        def res = s.trim()
        res = (res =~ /^\"(.+)\"$/).replaceFirst('$1')
        res = res.replace('\\', '')
        res = res.replace('%', 'PCT')
        res = res.replace('*', '')
        res = res.replace('&', ' and ')

        return res
    }

}
