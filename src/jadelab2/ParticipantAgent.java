package jadelab2;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.*;

public class ParticipantAgent extends Agent {

  private ParticipantAgentGui myGui;
  private double[] calendar = new double[8760]; //availability calendar
  Map<String, List<Integer>> reservations = new HashMap<>();


  private long startTime;
  private final long RESPONSE_TIMEOUT = 10000;


	protected void setup() {

    Random rand = new Random();
    //initialize random availability calendar
    for (int i = 0; i < calendar.length; i++) {
      calendar[i] = Math.round(rand.nextDouble() * 1000.0) / 1000.0;
    }

    System.out.println("Availability calendar for " + getAID().getLocalName() + " added");
    /*for (int i = 0; i < calendar.length; i++) {
      System.out.printf("%s: slot %2d: %.2f\n", getAID().getLocalName(), i, calendar[i]);
    }*/
    
	  System.out.println("Hello! " + getAID().getLocalName() + " is ready for a meeting.");
	  myGui = new ParticipantAgentGui(this);
	  myGui.display();

    DFAgentDescription dfd = new DFAgentDescription();
    dfd.setName(getAID());
    ServiceDescription sd = new ServiceDescription();
    sd.setType("participant-meeting");
    sd.setName("JADE-participant-meeting");
    dfd.addServices(sd);
    try {
      DFService.register(this, dfd);
    }
    catch (FIPAException fe) {
      fe.printStackTrace();
    }
		addBehaviour(new MeetingResponder());
    addBehaviour(new MeetingConfirmed());
  }
  protected void takeDown() {
    //book selling service deregistration at DF
    try {
      DFService.deregister(this);
    }
    catch (FIPAException fe) {
      fe.printStackTrace();
    }
  	myGui.dispose();
    System.out.println("Participant agent " + getAID().getName() + " terminated.");
  }


  //invoked from GUI, when purchase was ordered
	public void requestPressed()
	{
		addBehaviour(new RequestMeeting());
	}
  //check if a slot is reserved
  private boolean isReserved(int slot) {
      for (List<Integer> slots : reservations.values()) {
          if (slots.contains(slot)) {
              return true;
          }
      }
      return false;
  }

  private class RequestMeeting extends Behaviour {

    private AID[] participants;
    private MessageTemplate mt;
    private int repliesCnt = 0;
    private int step = 0;
    private double[][] schedule = new double[calendar.length][2];
    private int zeroCounter = 0;
    private String code = "meeting"+System.currentTimeMillis();

    public void action() {

      switch (step) {

        case 0:

          System.out.println(getAID().getLocalName() + ": I am looking for other participants");

          //update a list of known participants (DF)
          DFAgentDescription template = new DFAgentDescription();
          ServiceDescription sd = new ServiceDescription();
          sd.setType("participant-meeting");
          template.addServices(sd);

          try {

            DFAgentDescription[] result = DFService.search(myAgent, template);
            System.out.println(getAID().getLocalName() + ": the following participants have been found");
            participants = new AID[result.length-1];
            int idx = 0;
            for (int i = 0; i < result.length; ++i) {
              if (!result[i].getName().equals(getAID())) {//exclude self
                participants[idx++] = result[i].getName();
                System.out.println(participants[idx-1].getLocalName());
              }
            }
          } 
          catch (FIPAException fe) {
            fe.printStackTrace();
          }
          step = 1;
          break;
        case 1:
          ACLMessage qr = new ACLMessage(ACLMessage.QUERY_REF);
          for (int i = 0; i < participants.length; ++i) {
            qr.addReceiver(participants[i]);
          }
          qr.setConversationId(code);
          qr.setReplyWith(code); //unique value
          myAgent.send(qr);
          System.out.println(getAID().getLocalName() + ": Request sent to all participants");

          startTime = System.currentTimeMillis();
          step = 2;
          break;
      
        case 2:
          //Receive all proposals/refusals from participant agents
          mt = MessageTemplate.and(
                  MessageTemplate.MatchConversationId(code),
                  MessageTemplate.MatchPerformative(ACLMessage.INFORM_REF)
          );
          ACLMessage reply = myAgent.receive(mt);
          if (reply != null) {
            //Reply received
            repliesCnt++;
            System.out.println(getAID().getLocalName() + ": Reply received from " + reply.getSender().getLocalName() + ": (with slot,availability) (" + reply.getContent() + ") | Replies: " +  repliesCnt + "/" + participants.length);
            //Here we save the received availability calendar
            String content = reply.getContent();
            String[] parts = content.split(",");
            int slot = Integer.parseInt(parts[0]);
            double availability = Double.parseDouble(parts[1]);
            if(availability == 0.0) {
              zeroCounter++;
            } else {
              schedule[slot][0] += availability;
              schedule[slot][1] += 1.0;
            };

        
            if (repliesCnt >= participants.length) {
              int maxIndex = 0;
              double maxValue = 0.0;

              for(int i=0; i<calendar.length; i++) {
                if(calendar[i] > maxValue && !isReserved(i)) {
                  maxValue = calendar[i];
                  maxIndex = i;
                }
              }
              if(maxValue != 0.0)  {
                reservations.computeIfAbsent(code, k -> new ArrayList<>()).add(maxIndex); //reserve this slot
                schedule[maxIndex][0] += maxValue;
                schedule[maxIndex][1] += 1.0;
              } else {
                zeroCounter++;
              }
              System.out.println(getAID().getLocalName() + ": Added my own (slot,availability) (" + maxIndex + "," + maxValue + ")");
              
              step = 3;
            }
          } else {
            block(1000);
          }
          if (System.currentTimeMillis() - startTime > RESPONSE_TIMEOUT) {
			      System.out.println(getAID().getLocalName() + ": Timeout expired while waiting for participant responses.");
	          step = 4; 
	        }

          break;
        case 3:
          //Here we should process all received availability calendars and find a common meeting time
          System.out.println(getAID().getLocalName() + ": Processing received slots to find a common meeting slot... or no common slot.");
          
          int maxIndex = -1;
          double maxValue = 0.0;
          for(int i = 0; i < schedule.length; i++) {
            if(schedule[i][1] == participants.length+1 && schedule[i][0] > maxValue) {
              maxValue = schedule[i][0];
              maxIndex = i;
            }
          }
          if(maxIndex == -1) {
            if (zeroCounter == (participants.length + 1)) {
              System.out.println(getAID().getLocalName() + ": No meeting can be scheduled.");
              step = 4;
            } else {
              System.out.println(getAID().getLocalName() + ": No common meeting time found among current slots, requesting more slots...");
              repliesCnt = 0;
              zeroCounter = 0;
              step = 1;
            }

          } else {
            System.out.println(getAID().getLocalName() + ": Common meeting time found at slot " + maxIndex + " with total availability " + maxValue);
            System.out.println(getAID().getLocalName() + ": Informing all participants about the agreed meeting time...");
            ACLMessage inform = new ACLMessage(ACLMessage.INFORM);
            for (int i = 0; i < participants.length; ++i) {
              inform.addReceiver(participants[i]);
            }
            inform.setConversationId(code);
            inform.setReplyWith(code); //unique value

            inform.setContent(String.valueOf(maxIndex)); //agreed meeting time slot
            myAgent.send(inform);
            calendar[maxIndex] = 0.0; //mark this slot as busy

            reservations.remove(code);

            System.out.println(getAID().getLocalName() + ": Meeting confirmed at slot " + maxIndex);
            step = 4;
          }
          break;
        case 4:
          System.out.println(getAID().getLocalName() + ": Meeting request process finished.");
          step = 5;
          break;
      }

    }
    @Override
    public boolean done() {
      return (step == 5);
    }
  }

  private class MeetingResponder extends CyclicBehaviour {

    public void action() {
      //proposals only template
      MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.QUERY_REF);
      ACLMessage msg = myAgent.receive(mt);
      if (msg != null) {
        // QUERY_REF received. Reply with the availability calendar
        ACLMessage reply = msg.createReply();
        reply.setPerformative(ACLMessage.INFORM_REF);

        //unique value
        String code = msg.getReplyWith();

        //Here we set the content of the reply with the availability calendar
        int maxIndex = 0;
        double maxValue = 0.0;

        for(int i=0; i<calendar.length; i++) {
          if(calendar[i] > maxValue && !isReserved(i)) {
            maxValue = calendar[i];
            maxIndex = i;
          }
        }
        if(maxValue != 0.0)  {
          reservations.computeIfAbsent(code, k -> new ArrayList<>()).add(maxIndex); //reserve this slot
        }
        reply.setContent(maxIndex + "," + maxValue);

        myAgent.send(reply);
        System.out.println(getAID().getLocalName() + ": Sent best current option to " + msg.getSender().getLocalName());
      }
      else {
        block();
      }
    }
  }

  private class MeetingConfirmed extends CyclicBehaviour {

    public void action() {
      MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
      ACLMessage msg = myAgent.receive(mt);
      if (msg != null) {
        System.out.println(getAID().getLocalName() + ": Received meeting confirmation from " + msg.getSender().getLocalName());
        // Process the confirmed meeting time
        String content = msg.getContent();
        int agreedSlot = Integer.parseInt(content);
        calendar[agreedSlot] = 0.0; //mark this slot as busy

        String code = msg.getReplyWith();
        reservations.remove(code);

        System.out.println(getAID().getLocalName() + ": Meeting confirmed at slot " + agreedSlot);
      } else {
        block();
      }
    }
  }
}