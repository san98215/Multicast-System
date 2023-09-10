This is our Multicast System

Seth Nye, Davis Webster, David Korsunsky

In order to compile and run:

Start by going into the Coordinator directory and run the following command: "javac -d bin src/Coordinator.java"

Then, go into separate vcf nodes into the Participant directory and run the following command: "javac -d bin src/Participant.java"

From here stay in the same directory, If on two different VCF nodes, select one of the two to be the coordinator and the other to be the participant

Now type on the coordinator side: "java -cp bin Coordinator 'PP3-coordinator-conf.txt' "

Go to the other vcf for your participant(s) and enter : "java -cp bin Participant 'PP3-participant-conf#.txt' where '#' is the conf file for each specific participant


“ This project was done in its entirety by David Korsunsky, Davis Webster, and Seth Nye. We hereby
  state that we have not received unauthorized help of any form ”

