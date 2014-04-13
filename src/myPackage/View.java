package myPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;

public class View {
	private HashSet<String> view;
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
			return null;
		}
		Random r = new Random();
		int index = r.nextInt(arr.length);
		return (String) arr[index];
	}
	
	public static void union(View v, View w){
		v.view.addAll(w.view);
	}
}
