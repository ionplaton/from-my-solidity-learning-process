//SPDX-License-Identifier: GPL-3.0
pragma solidity >0.6.0 <0.9.0;

/*
A token is designed to represent something of value but also things like voting rights or discount vouchers. 
It can represent any fungible trading good. ERC stands for Ethereum Request for Comments. 
An ERC is a form of proposal and its purpose is to define standards and practices.
There are tokens that are fully-ERC20-compliant and tokens that are only partially-ERC20-compliant.
A full compatible ERC20 Token must implement 6 functions and 2 events.
I will write an example of partially-ERC20-compliant token, with 3 functions and 1 event.
*/

interface ERC20Interface {

/*
A fully compatible ERC20 Token must impleent 6 functions and 2 events (eips.ethereum.org/EIPS/eip-20). 
First 3 functions a mandatory. In this contract I will use only first three functions and one event.
*/
    function totalSupply() external view returns (uint);
    function balanceOf(address tokenOwner) external view returns (uint balance);
    function transfer(address to, uint tokens) external returns (bool success);
    
    event Transfer(address indexed from, address indexed to, uint tokens);
}

/*
I will create a token contract and override the three functions. 
The new contract called Platonius derives from the ERC20Interface
*/
contract Platonius is ERC20Interface {

/*
I am declaring the state variables of the contract. I am using the exactn names since most 
of them are part of the standard.
*/
    string public name = "Platonius";
    string public symbol = "PLAT"; 
    uint public decimals = 0; // 18 most used
    uint public override totalSupply; 
    // the override keyword is necessary because it crates a getter function called totalSupply,
    because the variable is public.

    address public founder;
    mapping(address => uint) public balances;
    // Here we will store the number of tokens of each address.  balances[0x1111...] = 100;

// Declaring the constructor
    constructor() {
        totalSupply = 1000000;
        founder = msg.sender;
        balances[founder] = totalSupply;
    }

// Copying the fonction from the interface and adding the override keyword. 
    function balanceOf(address tokenOwner) public view override returns(uint balance) {
        return balances[tokenOwner];
    }

// Implementing the third mandatory function from the interface, also override.
    function transfer(address to, uint tokens) public override returns(bool success) {
        require(balances[msg.sender] >= tokens);

        balances[to] += tokens;
        balances[msg.sender] -= tokens;
        
        emit Transfer(msg.sender, to, tokens);

        return true;
    }
}
