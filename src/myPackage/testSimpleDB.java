package myPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.simpledb.*;
import com.amazonaws.services.simpledb.model.*;


public class testSimpleDB {
	static AmazonSimpleDB sdb;
	static String myDomain = "Proj1b";
	static String myItem = "Servers";
	static String attributePrefix = "Server";
	public static void main(String[] args){
		
		
		try {
			sdb = new AmazonSimpleDBClient(new PropertiesCredentials(
					testSimpleDB.class.getResourceAsStream("AwsCredentials.properties")));
			System.out.println(sdb.listDomains());
			
			//If sdb doesn't exist somehow
			if(!sdb.listDomains().getDomainNames().contains(myDomain)){
				sdb.createDomain(new CreateDomainRequest(myDomain));
			}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		
		ArrayList<String> arr = new ArrayList<String>();
		arr.add("1.1.1.1");
		arr.add("2.2.2.2");
		arr.add("3.3.3.3");
		writeSDBView(arr, 3);
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		readSDBView();
	}
	
	public static ArrayList<String> readSDBView(){
		String selectExpression = "select * from " + myDomain + "";
		SelectRequest req = new SelectRequest(selectExpression);
		ArrayList<String> arr = new ArrayList<String>();
		
		//Get ip addresses stored in SimpleDB
		for(Item item : sdb.select(req).getItems()){
			for(Attribute attr : item.getAttributes()){
				System.out.println(attr.getName());
				System.out.println(attr.getValue());
				arr.add(attr.getValue());
			}
		}
		return arr;
	}
	
	
	//Takes two parameters:
	//1: List of ip addresses to be written
	//2: number of attributes stored in database previously(must remove extras)
	public static void writeSDBView(List<String> ips, int size){
		int i;
		//Replace previous attributes
		for(i = 0; i < ips.size(); i++){
			ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(attributePrefix + i).
					withValue(ips.get(i)).withReplace(true);
			sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem)
					.withAttributes(replaceAttribute));
		}
		
		//Delete extra attributes
		for(; i < size; i++){
			sdb.deleteAttributes(new DeleteAttributesRequest(myDomain, myItem).withAttributes(
					new Attribute().withName(attributePrefix + i)));
		}
	}
}
