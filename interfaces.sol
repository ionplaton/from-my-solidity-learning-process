//SPDX-License-Identifier: MIT
pragma solidity ^0.8.18;


/*
The real value of interfaces is in what they really allow us to do - to connect information. 
We don't have to copy and paste multiple contracts code over and over, we can access contract 
information through interfaces so we can connect our contracts in a way where we do not have 
to copy and paste code. Let's see how this works.
*/
contract Counter {
    uint public count;

    function increment() external {
        count += 1;
    }
}

/*
To interact with this contract without copy and pasting the code we will use interfaces. 
Let's create it. It will do two things - it will increment and it is going to count.
*/
interface ICounter { 
    function count() external view returns(uint);
    function increment() external;
}

/*
This interface is going to connect to the Counter contract. Think of it as a device that is 
going to connect. We have a Counter that can count and we have an interface just following 
all the rules of the interfaces, and it is going to count as well. How do we tie this all 
together? We will create another contract and we will see that we can interact between first 
contract and last one with this interface.
*/
contract MyContract {

/*
In the last contract we will have a function called incrementCounter, which will take an 
address as input and the address is going to count. It will be external. Our contract should 
be able to increment, so we can do that by bringing in ICounter (name of the interface) and 
set what we want the counter to be and we want it to be the address (_counter). Next we can 
grab our increments, we can count (increment()).
*/
    function incrementCounter(address _counter) external {
        ICounter(_counter).increment();
    }

// Now, let's create another function (getCount) so we could see the incriminations happening in the contract.
    function getCount(address _counter) external view returns(uint) {
        return ICounter(_counter).count();
    }
/*
Let's recap. We have an interface (ICounter) which has count and an increment. First 
contract (Counter) has a variable (count) and a function (increment). We a connecting them 
in our last contract (MyContract) with .increment() and .count().
*/
}
