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
import java.util.Random;

public class ParticipantAgent extends Agent {

  private ParticipantAgentGui myGui;
  private double[] calendar = new double[24]; //availability calendar


	protected void setup() {

    Random rand = new Random();
    //initialize random availability calendar
    for (int i = 0; i < 24; i++) {
      calendar[i] = rand.nextDouble();
    }

    System.out.println("Availability calendar for " + getAID().getLocalName() + ":");
    for (int i = 0; i < 24; i++) {
      System.out.printf("%s: Hour %2d: %.2f\n", getAID().getLocalName(), i+1, calendar[i]);
    }
    
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

  private class RequestMeeting extends Behaviour {

    private boolean finished = false;
    private AID[] participants;

    public void action() {

      System.out.println(getAID().getLocalName() + ": I am looking for other participants");

      //update a list of known participants (DF)
      DFAgentDescription template = new DFAgentDescription();
      ServiceDescription sd = new ServiceDescription();
      sd.setType("participant-meeting");
      template.addServices(sd);

      try {

        DFAgentDescription[] result = DFService.search(myAgent, template);
        System.out.println(getAID().getLocalName() + ": the following participants have been found");
        participants = new AID[result.length];

        for (int i = 0; i < result.length; ++i) {
          participants[i] = result[i].getName();
          System.out.println(participants[i].getLocalName());
        }
      }
      catch (FIPAException fe) {
        fe.printStackTrace();
      }



      finished = true;   // behaviour finishes after one execution
    }

    public boolean done() {
      return finished;
    }
  }

}