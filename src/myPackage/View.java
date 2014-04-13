package myPackage;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;

public class View {
	private HashSet<String> view;
	
//	public static void main(String[] args){
//		View v = new View();
//		System.out.println(choose(v));
//		insert(v, "1.1.1.1");
//		insert(v, "1.1.1.2");
//		insert(v, "1.1.1.3");
//		insert(v, "1.1.1.4");
//		System.out.println(choose(v));
//		
//		remove(v, "1.1.1.2");
//		System.out.println(v.view);
//		
//		View v2 = new View();
//		insert(v2, "1.1.1.3");
//		insert(v2, "1.1.1.4");
//		insert(v2, "1.1.1.5");
//		
//		union(v, v2);
//		System.out.println(v.view);
//		System.out.println(v2.view);
//		
//		shrink(v,2);
//		shrink(v2,1);
//		
//		System.out.println(v.view);
//		System.out.println(v2.view);
//	}
	
	public View(){
		view = new HashSet<String>();
	}
	
	public static void shrink(View v, int k){
		int i = 0;
		ArrayList<String> arr = new ArrayList<String>(v.view);
		
		//Creates random permutation, then remove first size - k entries
		Collections.shuffle(arr); 
		while(v.view.size() > k){
			v.view.remove(arr.get(i));
			i++;
		}
	}
	
	public static void insert(View v, String svrID){
		v.view.add(svrID);
	}
	
	public static void remove(View v, String svrID){
		v.view.remove(svrID);
	}
	
	public static String choose(View v){
		Object[] arr = v.view.toArray();
		if(arr.length == 0){
			try {
				return MyServlet.inetaddrToString(InetAddress.getByName("0.0.0.0"));
			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		Random r = new Random();
		int index = r.nextInt(arr.length);
		return (String) arr[index];
	}
	
	public static void union(View v, View w){
		v.view.addAll(w.view);
	}
	
	public static View copy(View v){
		View ret = new View();
		union(ret, v);
		return ret;
	}
	
	public static HashSet<String> getIPs(View v){
		return v.view;
	}
	
	public String toString(){
		String str = "";
		for(String s : view){
			if (!s.equals(null))
				str += ViewDB.convertToReadableIP(s) + ", ";
		}
		return str;
	}
}
