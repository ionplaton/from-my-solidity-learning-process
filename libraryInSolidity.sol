//SPDX-License-Identifier: MIT
pragma solidity ^0.8;


/*
Libraries are similar to Contracts, but are mainly intended for reuse. A Library contains functions which 
other contracts can call. It is very similar in a sense when we think about interfaces, except whereas 
interfaces are connecting contracts, but libraries are more like a storing of functional data that we 
can then apply to contracts. 
Let's take this theorizing of what a Library is and put it into some code. To bring a library we type 
the keyword library and call it Search.
*/
library Search {

// Let's say we want search through a database. Inside the Library we can write a public view function
    function indexOf(uint[] storage self, uint value) public view returns(uint) {
    
/*
How do we search through things? We will use the For Loop. We want this loop to return the index value 
of some integer we are searching for. For doing this we will use an if statement.
*/
        for(uint i=0; i < self.length; i++) if(self[i] == value) return i;
    }
}

/*
Just like that, we have created our first Library. Now, let's test this Library by creating a contract 
with a variable and a constructor for the beginning.
*/
contract Test {
    uint[] data;
    constructor() public {
        data.push(1);
        data.push(2);
        data.push(3);
        data.push(4);
        data.push(5);
    }

/*
Also we are going to make a function, which will check for a value (uint val), make it external view and return a integer. 
What we are going to do here is we are going to create a value and we are going to set it to the input.
*/
    function isValuePresent(uint val) external view returns(uint) {
        uint value = val;
        
 /*
 And then how do we actually connect the dots? How do we say: "Well, now search!". We want to search 
 through the data and set up the data to the array uint[] data and the value to our val input. 
 Do do that, we can directly grab our Search Library. From our Library, with dot notation, 
 we have access to our function, so we can grab indexOf and then we go into our arguments and write 
 data as first argument and value as second argument. Next we will return index.
 */
        uint index = Search.indexOf(data, value);
        return index;
    }
}
