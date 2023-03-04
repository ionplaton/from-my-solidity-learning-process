//SPDX-License-Identifier: GPL-3.0
pragma solidity >0.6.0 <0.9.0;

/*
A token is designed to represent something of value but also things like voting rights or discount vouchers. 
It can represent any fungible trading good. ERC stands for Ethereum Request for Comments. 
An ERC is a form of proposal and its purpose is to define standards and practices.
There are tokens that are fully-ERC20-compliant and tokens that are only partially-ERC20-compliant.
A full compatible ERC20 Token must implement 6 functions and 2 events.
I will write an example of fully-ERC20-compliant token.
*/

interface ERC20Interface {

    // A fully compatible ERC20 Token must impleent 6 functions and 2 events (eips.ethereum.org/EIPS/eip-20). 
    function totalSupply() external view returns (uint);
    function balanceOf(address tokenOwner) external view returns (uint balance);
    function transfer(address to, uint tokens) external returns (bool success);

    function allowance(address tokenOwner, address spender) external view returns (uint remaining);
    function approve(address spender, uint tokens) external returns (bool success);
    function transferFrom(address from, address to, uint tokens) external returns (bool success);

    event Transfer(address indexed from, address indexed to, uint tokens);
    event Approval(address indexed tokenOwner, address indexed spender, uint tokens);
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
    // because the variable is public.

    address public founder;
    mapping(address => uint) public balances;
    // Here we will store the number of tokens of each address.  balances[0x1111...] = 100;
    
    /* this mapping includes accounts approved to withdraw from a given account together with the
    withdrawal sum allowed for each of them.
    The keys are of type address and are the addresses of the token holders.
    The corresponding values are other mappings representing the addresses thata re allowed to 
    transfer from the holder's balance and the amount allowed to be transferred.
    */
    mapping(address => mapping(address => uint)) allowed;

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
    
    /*
    Next, I am implementing the function that will return the allowance of an address. 
    This is the getter function for the allowed state avariable.
    The function will return how many tokens have the tokenOwner allowed the spender to withraw.
    */
    function allowance(address tokenOwner, address spender) view public override returns(uint) {
        return allowed[tokenOwner][spender];
    }
    
    /*
    Implementing the approve function. Will be called by the tokenOwner to set the allowance, 
    which is the amount that can be spent. I will copy the fnction from the interface and change 
    it to public and add override.
    */
    function approve(address spender, uint tokens) public override returns(bool success) {
        require(balances[msg.sender] >= tokens);
        require(tokens > 0);
        
        // Updating the allowed mapping
        allowed[msg.sender][spender] = tokens;

        // The approval event should be triggered on any successful call
        emit Approval(msg.sender, spender, tokens);
        return true;
    }
    
    /*
    The last function from the interface allows the spender to withdraw or transfer from 
    the owner's account multiple times up to the allowance. This function also change the current allowance.
    */
    function transferFrom(address from, address to, uint tokens) external returns(bool success) {
        require(allowed[from][msg.sender] >= tokens);
        // msg.sender is the acount that has been approved by from before and it will transfer funds to a recipient called to
        
        require(balances[from] >= tokens);
        balances[from] -= tokens;
        allowed[from][msg.sender] -= tokens;
        balances[to] += tokens;

        // Before returning, I am emitting the transfer event
        emit Transfer(from, to, tokens);
        return true;
    }
}
