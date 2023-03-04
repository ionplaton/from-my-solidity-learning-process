//SPDX-License-Identifier: MIT
pragma solidity ^0.8.18;

contract Coin {

/* We are going to need two state variables that we are going to have to create here. One is going to be for the minter 
(the person who is going to be minting the tokens), and then another one is going to be balances to keep track of the balances. 
Our minter is going to be the address, because we want the creator to be the owner.
For the reason that we should be able to keep track of each address we will use mapping and set our address to a KeyStore value uint
*/
    address public minter;
    mapping(address => uint) public balances;
    
// We will make an event to allow our clients to react to the changes happening in our #smartcontract .
    event Sent(address from, address to, uint amount);

/* Let's set the constructor so when we deploy the contract our minter is going to equal the person that is calling 
the contract (msg.sender) and then sender checks for the caller, the person who is calling.
*/
    constructor() {
        minter = msg.sender;
    }

/*  We want to make new coins and send them to an address, and the other thing we want to do is we want to make sure that only the owner
can send these coins. We will add two arguments for the functions - receiver and amount. 
To be sure that only the owner can send these coins we will use require as our condition and we can say that 
msg.sender has to be equal to minter. Then how do we actually make the new coins and send them? Well, we can use our balances, 
which is keeping track of our mapping, and then create our list of receiver. This, we want it to be equal to the amount. 
But we don't want it to just equal the amount, because if we do, then every time that we mint it is just going to revert to the 
amount and it is not going to keep track of what we had before. One way to do this is to write '+=' in front of amount.
*/
    function mint(address receiver, uint amount) public {
        require(msg.sender == minter);
        balances[receiver] += amount;
    }

/* When using error we are letting solidity know that it is an error. We will have two integers as attributes which will 
show as the available amount versus the requested amount.
*/
    error insuficientBalance(uint requested, uint available);

/* Let's create a function that can send any amount of coins to an existing address. It will be very similar to the 
mint function - we are going to need an address and an amount. In the curly brackets we need to update 
our balances - msg.sender and receiver.
To prevent the spending of amount greater then the available balance we will create an if statemen and we are going to say 
if the amount is grater than balances of msg.sender then we will use a special keyword revert, which will stop the transaction 
from happening and provide the information regarding the transaction. We wil revert to insuficientBalance and then we can 
bring it in like an object and then set the parameters up.
*/
    function send(address receiver, uint amount) public {
        if(amount > balances[msg.sender])
        revert insuficientBalance({
            requested: amount,
            available: balances[msg.sender]
        });       
        balances[msg.sender] -= amount;
        balances[receiver] += amount;
        emit Sent(msg.sender, receiver, amount);
    }
}
