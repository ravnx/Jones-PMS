package util;

/*
 * Implementation of a pair from StackOverflow
 */

public class Pair<FirstType, SecondType> { 
	  // not final: Gson sets these when reading a person file
	  public FirstType first;
	  public SecondType second;
	  public Pair(FirstType first, SecondType second) { 
	    this.first = first; 
	    this.second = second; 
	  } 
	}