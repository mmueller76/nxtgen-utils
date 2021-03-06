package org.nxtgenutils.impl;

import net.sf.samtools.*;

import java.io.File;
import java.io.PrintWriter;
import java.io.FileNotFoundException;
import java.util.*;

import org.apache.log4j.Logger;
import org.nxtgenutils.io.impl.SimpleVCFParser;
import org.nxtgenutils.io.VCFSampleRecord;
import org.nxtgenutils.io.VCFRecord;
import org.nxtgenutils.io.VCFParser;

/**
 * This file is part of NxtGenUtils.
 * <p/>
 * NxtGenUtils is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * NxtGenUtils is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with NxtGenUtils.  If not, see <http://www.gnu.org/licenses/>.
 * <p/>
 * Created by IntelliJ IDEA.
 * User: mmuelle1
 * Date: 09-Jan-2012
 * Time: 16:37:26
 */

/**
 * Implementation of the ReadPhaser interface.
 * @author Michael Mueller
 */
public class PairedEndReadPhaser extends AbstractReadPhaser {

    private int assignedAUnique = 0;
    private int assignedBUnique = 0;
    private int assignedAMajority = 0;
    private int assignedBMajority = 0;
    private int assignedAmbiguous = 0;
    private String previousChromosome = "none";

    private static Logger logger = Logger.getLogger(PairedEndReadPhaser.class);

    /**
     * Constructs a read phaser instance to phase reads in a BAM file using genotype
     * information in a VCF file.
     *
     * @param mappingInput the BAM file to be phased
     * @param genotypeVCF  VCF file containing the genometype
     */
    public PairedEndReadPhaser(File mappingInput, File genotypeVCF) {
        super(genotypeVCF, mappingInput);
    }

    /**
     * Phases reads according to genotype information for sample A and sample B in
     * the VCF file. The names of the output BAM files will be generated automatically
     * from the input filename.
     *
     * @param sampleA the name of sample in the VCF file to be used as sample A
     * @param sampleB the name of sample in the VCF file to be used as sample B
     */
    public void phase(String sampleA, String sampleB) {

        String mappingInputPath = mappingInput.getAbsolutePath();

        File outputRead2SNP2Sample = new File(mappingInputPath.replace(".bam", ".read_snp_sample.tsv"));
        File outputRead2SampleSummary = new File(mappingInputPath.replace(".bam", ".read_sample_summary.tsv"));
        File outputSampleA = new File(mappingInputPath.replace(".bam", ".phased." + sampleA + ".bam"));
        File outputSampleB = new File(mappingInputPath.replace(".bam", ".phased." + sampleB + ".bam"));
        File outputAmbiguous = new File(mappingInputPath.replace(".bam", ".phased.ambiguous.bam"));
        File outputPhasingSNPs = new File(mappingInputPath.replace(".bam", ".phasingSnps.vcf"));

        this.phase(sampleA, sampleB, outputSampleA, outputSampleB, outputAmbiguous, outputPhasingSNPs, outputRead2SNP2Sample, outputRead2SampleSummary);

    }

    /**
     * Phases reads according to genotype information for sample A and sample B in
     * the VCF file.
     *
     * @param sampleA           the name of sample A
     * @param sampleB           the name of sample B
     * @param outputSampleA     path to BAM file for reads matching genotype of sample A
     * @param outputSampleB     path to BAM file for reads matching genotype of sample B
     * @param outputAmbiguous   path to BAM file for reads with ambiguous genotype information
     * @param outputPhasingSNPs path to VCF file for SNPs used to phase reads
     * @param outputRead2SNP2Sample path to TSV file for read to SNP to sample mapping
     */
    public void phase(String sampleA, String sampleB, File outputSampleA, File outputSampleB, File outputAmbiguous, File outputPhasingSNPs, File outputRead2SNP2Sample, File outputRead2SampleSummary) {

        String mappingInputPath = mappingInput.getAbsolutePath();
        this.mappingIndex = new File(mappingInputPath + ".bai");

        PrintWriter pwRead2SNP2Sample = null;
        try {
            pwRead2SNP2Sample = new PrintWriter(outputRead2SNP2Sample);
        } catch (FileNotFoundException e) {
            logger.error("Unable to open output file for read to SNP to sample mapping.", e);
            System.exit(1);
        }


        //create file writer for VCF file containing
        //SNPs used for phasing

        PrintWriter writerVcf = null;
        try {
            writerVcf = new PrintWriter(outputPhasingSNPs);
        } catch (FileNotFoundException e) {
            logger.error("Expception while opening VCF file to write phasing SNPs.", e);
        }



        //write header information to VCF file
        VCFParser parser = new SimpleVCFParser(genotypeVCF);
        parser.setHeterozygousMinorAlleleFrequencyCutoff(minorAlleleFrequencyCutoff);

        for (String headerLine : parser.getHeaderLines()) {
            writerVcf.println(headerLine);
            writerVcf.flush();
        }



        //create map of maps to store read-to-SNP-to-allele mapping
        Map<SAMRecord, Map<VCFRecord, String>> read2SNP2Sample = new HashMap<SAMRecord, Map<VCFRecord, String>>();

        //iterate over SNPs in VCF file
        Iterator<VCFRecord> vcfIterator = parser.iterator();

        int snpCount = 0;
        int informativeSNPCount = 0;
        int exonicSNPCount = 0;

        logger.info("scaning BAM file for alignments covering informative SNP positions...");

        String chr = "0";
        while (vcfIterator.hasNext()) {

            VCFRecord vcfRecord = vcfIterator.next();

            snpCount++;
            if (snpCount % 100000 == 0) {
                logger.info(snpCount + " SNPs processed, " + read2SNP2Sample.size() + " reads in cache...");
            }

            VCFSampleRecord recordSampleA = vcfRecord.getSampleRecord(sampleA);
            VCFSampleRecord recordSampleB = vcfRecord.getSampleRecord(sampleB);

            //if the genotype of sample A and sample B differ
            //AND the genotype has been called for both samples
            //AND both genotypes are homozygous
            if (vcfRecord.getFilterValue().equals("PASS") &&
                    !recordSampleA.getGenotype().equals(recordSampleB.getGenotype())
                    && !recordSampleA.getGenotype().equals("./.") && !recordSampleB.getGenotype().equals("./.")
                    && !recordSampleA.isHeterozygous() && !recordSampleB.isHeterozygous()) {

                informativeSNPCount++;


                //get SNP position
                chr = vcfRecord.getChromsome();
                int snpPos = vcfRecord.getPosition();
//                if (!chr.equals(this.previousChromosome)) {
//
//                    clearReadMap(read2SNP2Sample,-1, writerA, writerB, writerAmb, sampleA, sampleB, writerVcf);
//
//                    this.previousChromosome = chr;
//
//                } else {
//                    //clear all reads from read cash that are downstream of the SNP position
//                    //and can thus no longer overlap with the current SNP
//                    clearReadMap(read2SNP2Sample,snpPos, writerA, writerB, writerAmb, sampleA, sampleB, writerVcf);
//                }


                Map<SAMRecord, Integer> overlappingReads = getOverlappingReads(vcfRecord);
                if (overlappingReads.keySet().size() > 0) {
                    exonicSNPCount++;
                    writerVcf.println(vcfRecord.getVcfString());
                }

                //assignReadsToSample(vcfRecord, overlappingReads, sampleA, sampleB, read2SNP2Sample);
                writeReadsToSampleInformation(vcfRecord, overlappingReads, sampleA, sampleB, pwRead2SNP2Sample);

            }

        }

        pwRead2SNP2Sample.close();
        writerVcf.close();

        logger.info(snpCount + " SNPs processed...");


        //final cleanup of reads still in the cache
        //clearReadMap(read2SNP2Sample, -1, writerA, writerB, writerAmb, sampleA, sampleB, writerVcf);

         //phase BAM files
        Map<String, Integer[]> read2Sample = readReadsToSampleInformation(outputRead2SNP2Sample, sampleA, sampleB);
        logger.info("writing read to sample assignment summary...");
        writeReadToSampleInformationSummary(read2Sample, outputRead2SampleSummary, sampleA, sampleB);


        logger.info("phasing alignments file...");           
        //open mapping input to get file header
        SAMFileReader samFileReader = new SAMFileReader(mappingInput, mappingIndex);

        //create BAM writers for phased reads
        SAMFileWriter writerA = new SAMFileWriterFactory().makeBAMWriter(samFileReader.getFileHeader(), false, outputSampleA);
        SAMFileWriter writerB = new SAMFileWriterFactory().makeBAMWriter(samFileReader.getFileHeader(), false, outputSampleB);
        SAMFileWriter writerAmb = new SAMFileWriterFactory().makeBAMWriter(samFileReader.getFileHeader(), false, outputAmbiguous);

        Iterator<SAMRecord> readIterator = samFileReader.iterator();

        while(readIterator.hasNext()){

            SAMRecord read = readIterator.next();
            String readName = read.getReadName();
            if(read2Sample.keySet().contains(readName)){

                int snpCountSampleA = read2Sample.get(readName)[0];
                int snpCountSampleB = read2Sample.get(readName)[1];

                if(snpCountSampleA > snpCountSampleB){

                    if(snpCountSampleB == 0){
                        assignedAUnique++;
                    } else {
                        assignedAMajority++;
                    }

                    writerA.addAlignment(read);

                } else if (snpCountSampleA < snpCountSampleB){

                    if(snpCountSampleA == 0){
                        assignedBUnique++;
                    } else {
                        assignedBMajority++;
                    }

                    writerB.addAlignment(read);

                } else {

                    assignedAmbiguous++;

                    writerAmb.addAlignment(read);

                }

            }

        }

        //close mapping input
        samFileReader.close();

        //close output BAM files
        writerA.close();
        writerB.close();
        writerAmb.close();

        logger.info(snpCount + " SNPs in input VCF file");
        logger.info(informativeSNPCount + " informative " + sampleA + "/" + sampleB + " SNPs found");
        logger.info(exonicSNPCount + " SNPs with read coverage");
        logger.info(assignedAUnique + " alignments uniquely assigned to sample " + sampleA);
        logger.info(assignedBUnique + " alignments uniquely assigned to sample " + sampleB);
        logger.info(assignedAMajority + " alignments assigned based on the majority of SNPs mapping to sample " + sampleA);
        logger.info(assignedBMajority + " alignments assigned based on the majority of SNPs mapping to sample " + sampleB);
        logger.info(assignedAmbiguous + " alignments unassigned because of ambiguous SNP pattern");

    }


    private void clearReadMap(Map<SAMRecord,
            Map<VCFRecord,
                    String>> read2SNP2Sample,
                              int snpPos,
                              SAMFileWriter outputSampleA,
                              SAMFileWriter outputSampleB,
                              SAMFileWriter outputSampleAmbiguous,
                              String sampleA,
                              String sampleB,
                              PrintWriter writerVcf) {





        Set<SAMRecord> toBeRemoved = new HashSet<SAMRecord>();
        Map<String, Map<Integer, VCFRecord>> phasingSNPs = new LinkedHashMap<String, Map<Integer, VCFRecord>>();
        //set paired-end flag to true

        for (SAMRecord read : read2SNP2Sample.keySet()) {
            read.setReadPairedFlag(true);    
        }

        for (SAMRecord read : read2SNP2Sample.keySet()) {

            if (read.getAlignmentEnd() < snpPos || snpPos == -1) {

                Set<String> sampleAssignment = new HashSet<String>();

                int sampleACount = 0;
                int sampleBCount = 0;

                for (VCFRecord snp : read2SNP2Sample.get(read).keySet()) {

                    String sample = read2SNP2Sample.get(read).get(snp);
                    sampleAssignment.add(sample);

                    if (sample.equalsIgnoreCase(sampleA)) {
                        sampleACount++;
                    } else if (sample.equalsIgnoreCase(sampleB)) {
                        sampleBCount++;
                    }

                    if (!phasingSNPs.containsKey(snp.getChromsome())) {
                        phasingSNPs.put(snp.getChromsome(), new TreeMap<Integer, VCFRecord>());
                    }

                    phasingSNPs.get(snp.getChromsome()).put(snp.getPosition(), snp);

                }

                //get mate

                SAMRecord mate = null;
                try{


                    String mateChr = read.getMateReferenceName();
                    int mateAlignStart = read.getMateAlignmentStart();

                    if(!mateChr.equals("*")){
                        //open queriable SAM file reader to find mates
                        SAMFileReader samFileReader = new SAMFileReader(mappingInput, mappingIndex);
                        samFileReader.setValidationStringency(SAMFileReader.ValidationStringency.SILENT);

                        Iterator<SAMRecord> result = samFileReader.queryAlignmentStart(mateChr, mateAlignStart);
                        while(result.hasNext()){
                            SAMRecord r = result.next();
                            if(r.getReadName().equals(read.getReadName())){
                                mate = r;
                            }
                        }

                        samFileReader.close();

                    }

                } catch(Exception e){

                    logger.error("Exception while phasing mate of read pair" + read.getReadName() + ".", e);

                }


                if (sampleAssignment.size() == 1) {

                    String sampleName = sampleAssignment.iterator().next();

                    if (sampleName.equals(sampleA)) {
                        outputSampleA.addAlignment(read);
                        this.assignedAUnique++;
                        if(mate != null){
                            outputSampleA.addAlignment(mate);
                            this.assignedAUnique++;
                        }
                    } else {
                        outputSampleB.addAlignment(read);
                        this.assignedBUnique++;
                        if(mate != null){
                            outputSampleB.addAlignment(mate);
                            this.assignedBUnique++;
                        }
                    }

                } else if (sampleACount > sampleBCount) {
                    outputSampleA.addAlignment(read);
                    this.assignedAMajority++;
                    if(mate != null){
                        outputSampleA.addAlignment(mate);
                        this.assignedAMajority++;
                    }
                } else if (sampleACount < sampleBCount) {
                    outputSampleB.addAlignment(read);
                    this.assignedBMajority++;
                    if(mate != null){
                        outputSampleB.addAlignment(mate);
                        this.assignedBMajority++;
                    }
                } else {
                    outputSampleAmbiguous.addAlignment(read);
                    this.assignedAmbiguous++;
                    if(mate != null){
                        outputSampleAmbiguous.addAlignment(mate);
                        this.assignedAmbiguous++;
                    }
                }

                toBeRemoved.add(read);

            }

        }



        for (SAMRecord read : toBeRemoved) {
            read2SNP2Sample.remove(read);
        }

        //write SNPs used for phasing to VCF
        for (String chromosome : phasingSNPs.keySet()) {

            Map<Integer, VCFRecord> snpsByPosition = phasingSNPs.get(chromosome);
            for (Integer pos : snpsByPosition.keySet()) {
                writerVcf.println(snpsByPosition.get(pos).getVcfString());
                writerVcf.flush();
            }

        }


    }


}
