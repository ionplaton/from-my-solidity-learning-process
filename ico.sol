//SPDX-License-Identifier: MIT
pragma solidity ^0.8;

/*
An Initial Coin Offering (ICO) is a type of crowdfunding using cryptocurrencies.
An ICO can be a source of capital for startup companies that offers investors some
units of a new cryptocurrency or crypto-token in exchange for a well-known and valuable
cryptocurrency like Ethereum.

- Our ICO will be a Smart Contract that accepts ETH in exchange for our token named Platonius (PLAT).
- The Platonius token is a fully compliant ERC20 token and will be generated at the ICO time.
- Investors will send ETH to the ICO contract's address and in return, they'll get an amount of Platonius.
- There will be a deposit address (EOA account) that automatically receives the ETH sent to the ICO contract.
- PLAT token price is 1PLAT = 0.0001Eth = 10^15 wei, 1Eth = 1000 PLAT.
- The minimum investment is 0.01 ETH and the maximum investment is 5 ETH.
- The ICO Hardcap is 300 ETH.
- The ICO will have an admin that specifies when the ICO starts and ends.
- The ICO ends when the Hardcap or the end time is reached (whichever comes first).
- The PLAT token will be tradable only after a specific time set by the admin.
- In case of an emergency the admin could stop the ICO and could also change the deposit address in case it gets compromised.
- The ICO can be in one of the following states: beforeStart, runing, afterEnd, halted.
- An we will also implement the possibility to burn the tokens that were not sold in the ICO.
- After an investment in the ICO the Invest event will be emitted.

I will start creating the ICO contract and declare the state variables and the constructor of the contract.
There are more approaches how to do an ICO. A recommended one is to drive the ICO from the token contract.
In my git hub I have a contract for a fully-ERC20-compliant token 
(https://github.com/ionplaton/down-the-rabbit-hole/blob/main/learning-solidity/fully-ERC20-compliant-token.sol). 
I will use paste it here and start writing the ICO contract next.
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
    // because the variable is public

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

    /*
    Implementing the third mandatory function from the interface, also override. 
    Added keyword virtual to be able to override this function in the ICO contract
    */
    function transfer(address to, uint tokens) public virtual override returns(bool success) {
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
    function transferFrom(address from, address to, uint tokens) virtual override public returns(bool success) {
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


// Declarin a new contract PlatoniusICO that derives from the Platonius contract with ERC20 Token.
contract PlatoniusICO is Platonius {

     /* 
     The ICO will have an admin that is the account that deploys the contract, 
     can stop the ICO or can change the deposit address. 
     */
     address public admin;
     address payable public deposit; // The address that gets the Ether sent to the contract.
     uint tokenPrice = 0.001 ether; // 1ETH = 1000 PLAT, 1PLAT = 0.001ETH
     uint public hardCap = 300 ether; // The maximum amount of Ether that can be invested
     uint public raisedAmount;
     uint public saleStart = block.timestamp; // the ICO is starting at the moment of deployment
     uint public saleEnd = block.timestamp + 604800; // ICO ends in one week. 604800 sec = 1 week
     uint public tokenTradeStart = saleEnd + 604800; // Tokens will be locked for one week after ICO end
     uint public maxInvestment = 5 ether;
     uint minInvestment = 0.1 ether;

     enum State {beforeStart, running, afterEnd, halted}
     State public icoState;

     /* 
     Declaring the constructor. It will initialize the depozit address to its argument 
     and the admin of the contract to the address that deploys the contract
     */
     constructor(address payable _deposit) {
         deposit = _deposit;
         admin = msg.sender;
         icoState = State.beforeStart;
     }

     // Declaring a function modifier that will applied to the functions that should be called only by admin
     modifier onlyAdmin() {
         require(msg.sender == admin);
         _;
     }
     
     //In case when something bad happens or there is an emergency
     function halt() public onlyAdmin {
         icoState = State.halted;
     }

     // To resume the ICO 
     function resume() public onlyAdmin {
         icoState = State.running;
     }

     // To change the deposit address (if it was compromised)
     function changeDepositAddress(address payable newDeposit) public onlyAdmin {
         deposit = newDeposit;
     }

     // To return the state of the ICO
     function getCurrentState() public view returns(State) {
         if(icoState == State.halted) {
             return State.halted;
         } else if(block.timestamp < saleStart) {
             return State.beforeStart;
         } else if(block.timestamp >= saleStart && block.timestamp <= saleEnd) {
             return State.running;
         } else {
             return State.afterEnd;
         }
     }

     event Invest(address investor, uint value, uint tokens);

     /* 
     The invest function is the main function of the ICO. 
     It will be called when somebody sends ETH to the contract and receives PLAT in turn.
     */
     function invest() payable public returns(bool) {
         icoState = getCurrentState();
         require(icoState == State.running);
         require(msg.value >= minInvestment && msg.value >= maxInvestment);

         // Add the value sent with the current transaction to the already raised amount
         raisedAmount += msg.value;

         // Checking if the raised amount is less than or equal to hardCap.
         require(raisedAmount <= hardCap);

         // Calculating the number of tokens the user will get for the Ether he has just sent
         uint tokens = msg.value / tokenPrice;

         // Adding the tokens to the investor's balance and subtracting them from the founder's balance
         balances[msg.sender] += tokens;
         balances[founder] -= tokens;
         // Balances is a mapping variable that was declared in the ERC20 token contract and inherited
         
         // Transfering to the deposit address the amount of wei sent to the contract
         deposit.transfer(msg.value);

         // Emitting an event
         emit Invest(msg.sender, msg.value, tokens);
         return true;
     }

     // Automatically called when somebody sent Ether directly to the contract's address
     receive() payable external {
         invest();
     }

     // copping the function from the ERC20 contract an pasting it here. Delete the keyword virtual
     function transfer(address to, uint tokens) public override returns(bool success) {
     
        // Adding requirement that the current timestamp is greater than the token trade start
         require(block.timestamp > tokenTradeStart);

         // Calling the transfer function of the base contract called Platonius
         Platonius.transfer(to, tokens);
         return true;
     }

     // copping the function from the ERC20 contract an pasting it here. Delete the keyword virtual
     function transferFrom(address from, address to, uint tokens) public override returns (bool success) {
         require(block.timestamp > tokenTradeStart);
         Platonius.transferFrom(from, to, tokens);
         return true;
     }

     // Burning the tokens
     function burn() public returns(bool) {
         icoState = getCurrentState();
         require(icoState == State.afterEnd);
         balances[founder] = 0;
         return true;
     }
}
