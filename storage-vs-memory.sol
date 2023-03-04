//SPDX-License-Identifier: MIT
pragma solidity ^0.8;

/*
Understanding the storage and memory is not always intuitive. Having a good understanding of 
storage versus memory is really important. 
Let's create a contract with a public dynamic array of strings and initialize it with two values.
*/
contract StorageVsMemory {
    string[] public cities = ['Paris', 'Chisinau'];

/*
Now, we will create a function that will try to modify the dynamic array using memory variable.
The variable "save1" will be stored in memory, not in storage, and it is initialized with 
the state variable called "cities" (save1 = cities). 
It is mandatory for an array, string, or struct to specify memory or storage. 
If I will delete the word "memory" we will see an error.
Also, in our function we want to change the first element (the element with the index 0)
of the array save1[0] to "Berlin". 
*/
    function memorySave() public {
        string[] memory save1 = cities;
        save1[0] = 'Berlin';
    }
/*
Next, we will create a similar function where we will change only the save1 array to be 
declared of type storage, storage being the location where it will be saved. 
*/
    function storageSave() public {
        string[] storage save1 = cities;
        save1[1] = 'Berlin';
    }
/*
Note that in each function, we changed only the variable defined inside of the function 
and not the state variable defined at the contract level.

After deploing the contract:
call the first function (memorySave()) that one that used the memory variable. 
We see that there is not any error and we will check the array again. We will check if it has changed the state variable. 
We can see that the state variable (cities) was not modified, since the first element (index 0) 
of the dynamic array remained unchanged. It wasn't changed to "Berlin", 
so the function worked on a copy, not on the state variable, on the original.  

Now, we will call the second function (storageSave()), the one with the storage variable. 
Also, we can not see any errors. We are going to check the contents of the array. 
The first element (index 0) remained unchanged - "Paris", but the second element (index 1) 
was changed to "Berlin" as expected.
*/
}
