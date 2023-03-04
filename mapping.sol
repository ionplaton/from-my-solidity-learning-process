//SPDX-License-Identifier: MIT
pragma solidity ^0.8;

/*
The mapping it is a data structure that holds key=>value pairs, where all keys must have the same type 
and values must have the same type as well. The keys can not be of types mapping, dynamic array, 
enum or struct. The values can be any type including mapping. 

To understand better what is mapping we will create a smart contract for an auction, where the users 
will bid an ETH value for a product or service and the highest bidder will win.
*/
contract MappingInSolidity {

/*
 We have to store both the address of the bidder and the value he bid. So we will declare a new public 
 variable of type mapping called 'bids'. To declare a mapping we will use a pair of parentheses after 
 the keyword mapping and between parentheses keys and the values, in our case the type of the keys 
 is address, and the type of values is uint. 
 Our mapping will have the keys of type address and the values of type uint. 
 The values will be the amount of wei sent by each address to contract.
 */
    mapping(address => uint) public bids;

/*
So we will declare a payable (provides a mechanism for the contract to receive funds in Ether) and public 
function called bid(). Inside the function we will add bids[msg.sender] = msg.value.

msg.sender is the address that calls the function in a transaction and msg.value is the value in wei sent 
when calling the function. Both of them are global predefined variables in Solidity.
In fact, we are going to add the key pair address=>value to the mapping. 
The address that calls the function and the value sent are added to the mapping. 
*/
    function bid() payable public {
        bids[msg.sender] = msg.value;
    }
}
