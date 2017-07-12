package edu.umn.galaxyp;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

public class ValidateFastaDatabase {

    private static Logger logger = Logger.getLogger(ValidateFastaDatabase.class.getName());

    // custom HashMap that contains list of databases and the number of times
    // they appear in the FASTA file
    private MultiSet<Header.DatabaseType> databaseTypeMultiSet;

    public MultiSet<Header.DatabaseType> getDatabaseTypeMultiSet() {
        return databaseTypeMultiSet;
    }

    public void addDatabaseType(Header.DatabaseType value){
        this.databaseTypeMultiSet.add(value);
    }

    // constructor
    public ValidateFastaDatabase(){
        this.databaseTypeMultiSet = new MultiSet<>();
    }

    // runs checks on supplied FASTA database
    public static void main(String[] args) {

        // construct empty instance of ValidateFastaDatabase, to record database types
        ValidateFastaDatabase vfd = new ValidateFastaDatabase();

        // input path
        Path fastaPath = Paths.get(args[0]);

        // output path for good and bad FASTAs
        Path outGoodFASTA = Paths.get(args[1]);
        Path outBadFASTA = Paths.get(args[2]);

        // if true, the presence of any invalid sequences triggers an exit code of 1
        boolean crash_if_invalid = Boolean.valueOf(args[3]);

        // if true, checks that is not a DNA or RNA sequence
        boolean checkIsProtein = Boolean.valueOf(args[4]);

        // if true, only outputs FASTA entries with an accession number
        boolean checkHasAccession = Boolean.valueOf(args[5]);

        // if true, checks that is greater than a minimum length
        boolean checkLength = Boolean.valueOf(args[6]);

        int minimumLength = 0;
        if (checkLength){
            minimumLength = Integer.valueOf(args[7]);
        }

        vfd.readAndWriteFASTAHeader(fastaPath,
                crash_if_invalid,
                outGoodFASTA,
                outBadFASTA,
                checkIsProtein,
                checkLength,
                minimumLength,
                checkHasAccession);

        System.out.println("Database Types");
        System.out.println(vfd.getDatabaseTypeMultiSet().toString());
    }

    /**
     * takes path to header, reads in FASTA file, and writes out sorted FASTA
     * @param inPath  path to FASTA file to be read in(NIO Path object)
     *  @param crash_if_invalid  if true, a badly formatted header (the first) will immediately cause a System.exit(1)
     * @param checkLength
     * @param minimumLength
     * @param checkHasAccession
     */
    public void readAndWriteFASTAHeader(Path inPath,
                                        boolean crash_if_invalid,
                                        Path outPathGood,
                                        Path outPathBad,
                                        boolean checkIsProtein,
                                        boolean checkLength,
                                        int minimumLength,
                                        boolean checkHasAccession){

        Header headerParsed = null;
        StringBuilder sequence = new StringBuilder(); // allows us to append all sequences of line

        try (BufferedWriter bwGood =
                     Files.newBufferedWriter(outPathGood);
             BufferedWriter bwBad = Files.newBufferedWriter(outPathBad);
             BufferedReader br = Files.newBufferedReader(inPath)){

            String line = br.readLine();

            // while there are still lines in the file
            while (line != null) {
                // indicates FASTA header line
                if (line.startsWith(">")){
                    String header = line + "\n";

                    // while there are still lines in the file and the next line is not a header
                    while ((line = br.readLine()) != null && !line.startsWith(">")){
                        sequence.append(line).append("\n");
                    }

                    // record that is sequentially updated
                    FastaRecord current_record = new FastaRecord(header, sequence.toString());
                    this.addDatabaseType(current_record.getDatabaseType());

                    // isDnaOrRna
                    boolean passDnaOrRna = passDnaOrRna(checkIsProtein, current_record);

                    // Length checking
                    boolean passBelowMinimumLength = passBelowMinimumLength(checkLength, minimumLength, current_record);

                    // accession checking
                    boolean passAccession = passAccession(checkHasAccession, current_record);

                    // write FASTA header and sequence to either good or bad file
                    writeFasta(sequence, bwGood, bwBad, header,
                            current_record.isValidFastaHeader(),
                            passDnaOrRna,
                            passBelowMinimumLength,
                            passAccession);

                    // empty the sequence builder to allow for appending the next sequence
                    sequence.setLength(0);
                }
            }

        } catch(IOException e) {
            logger.severe("FASTA file not found: " + e.toString());
        }
    }

    /**
     * checks if we should filter out FASTA database entries without accession numbers
     * @param checkHasAccession true if checking for a successful accession number parsing
     * @param current_record FASTA database entry currently in memory
     * @return true either if 1) we don't want to check for a valid accession number or
     *  2) sequence has a valid accession number
     */
    public boolean passAccession(boolean checkHasAccession, FastaRecord current_record) {
        boolean passAccession;// if checkHasAccession is false, then passAccession should always be true
        if (!checkHasAccession) {
            passAccession = true;
        } else {
            passAccession = current_record.getHasAccession();
        }
        return passAccession;
    }

    /**
     * checks if we should check for length, and if so, checks length
     * @param checkLength true if running length check
     * @param minimumLength if running length check, length of sequence is compared to this number
     * @param current_record FASTA database entry currently in memory
     * @return true either if 1) we don't want to check for length, or 2) sequence is above minimum length
     */
    public boolean passBelowMinimumLength(boolean checkLength, int minimumLength, FastaRecord current_record) {
        boolean passBelowMinimumLength;// if checkLength is false, then passBelowMinimumLength should always be true
        if (!checkLength) {
            passBelowMinimumLength = true;
        } else {
            passBelowMinimumLength = current_record.getSequenceLength() > minimumLength;
        }
        return passBelowMinimumLength;
    }

    /**
     * should we check if the sequence is a valid protein sequence?
     * @param checkIsProtein if true, run check against DNA and RNA alphabets
     * @param current_record FASTA database entry currently in memory
     * @return true either if 1) we don't want to check if sequence is valid, or 2) sequence is a valid AA sequence
     */
    public boolean passDnaOrRna(boolean checkIsProtein, FastaRecord current_record) {
        boolean passDnaOrRna;// if checkIsProtein is false, then passDnaOrRna should always be true
        if (!checkIsProtein) {
            passDnaOrRna = true;
        } else {
            // is the sequence dna or rna?
            passDnaOrRna = current_record.isDnaSequence() || current_record.isRnaSequence();
        }
        return passDnaOrRna;
    }

    /**
     * write given (raw) header and sequence to specified fasta file
     * @param sequence raw FASTA sequence from file. May include end of line characters
     * @param bwGood  buffered writer object pointing to good output file
     * @param bwBad buffered writer object pointing to bad output file
     * @param header raw FASTA header from file. May contain end of line characters, etc
     * @param isValidHeader true if header passed the Compomics parse
     * @param passDnaOrRna true if 1) we are not checking for valid AA sequence, or 2) is a valid AA sequence
     * @param passBelowMinimumLength true if 1) we are not checking for length, or 2) is above minimum length
     * @param passAccession true if 1) we are not excluding database entries w/o headers, or 2) an accession number was successfully extracted
     * @throws IOException if invalid paths are given for good and bad output databases.
     */
    private static void writeFasta(StringBuilder sequence,
                                   BufferedWriter bwGood,
                                   BufferedWriter bwBad,
                                   String header,
                                   boolean isValidHeader,
                                   boolean passDnaOrRna,
                                   boolean passBelowMinimumLength,
                                   boolean passAccession) throws IOException {
        // Write to file
        // isDnaOrRna can only be true if checkIsProtein is true
        // same for checkLength
        if (isValidHeader && passDnaOrRna && passBelowMinimumLength && passAccession) {
            bwGood.write(header, 0, header.length());
            bwGood.write(sequence.toString(), 0, sequence.length());
        } else {
            bwBad.write(header, 0, header.length());
            bwBad.write(sequence.toString(), 0, sequence.length());
        }
    }

}
